package com.circaso.integrations.impl.payhero

import com.circaso.infrastructure.api.operation
import com.circaso.integrations.api.CollectionProviders
import com.circaso.integrations.api.FeeKind
import com.circaso.integrations.api.FeeSource
import com.circaso.integrations.api.ProviderFee
import com.circaso.integrations.api.ProviderFeeBand
import com.circaso.integrations.api.ProviderFeeTable
import com.circaso.integrations.api.ProviderFees
import com.circaso.shared.FiatAmount
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * PayHero/FinoQ's own charges, from the two tables it publishes without authentication. No API of theirs returns
 * the fee of a payment or a payout, so these tables are all there is.
 *
 * - **Collections:** `GET /api/transaction_fees`, bands of `amount_from`, `amount_to`, `price` (whole shillings).
 *   Asked first of the host the prompt itself goes to ([PayHeroHosts.LEGACY_URL]), then of
 *   [PayHeroHosts.API_URL]; on 2026-11-23 the two differed below KES 50. One row of the live table is malformed
 *   (`amount_to` below `amount_from`); such rows are dropped.
 * - **Payouts:** `GET https://api.payhero.africa/api/pricing`, the data behind the public pricing page: for KES, per
 *   recipient, bands that give the fee at the band's first and last amount. The fee in between is **interpolated in
 *   a straight line and rounded up to the cent**. PayHero publishes no formula: the bands read as a flat part plus
 *   0.8 % (a straight line fits every one of them exactly), but until a real payout's charge has been compared with
 *   this, treat it as an estimate.
 *
 * **Refresh.** A table is fetched the first time a fee is asked and kept in this node's memory. It is fetched again
 * once it is [REFRESH_EVERY] old; if that fails, the kept table keeps answering (as `CACHED`, with its age) until it is
 * [KEEP_FOR] old, and a failed fetch is not tried again for [RETRY_AFTER]. With no table, or an amount no band
 * covers, the answer is `UNKNOWN` and no fee: the caller falls back to the table staff set in the admin.
 */
class PayHeroFees(hosts: PayHeroHosts, private val pricing: HttpClient, private val clock: Clock = Clock.systemUTC()) :
    ProviderFees {
    private val log = LoggerFactory.getLogger(PayHeroFees::class.java)
    private val tariffHosts = listOf(hosts.legacy, hosts.api)
    private val collections = Kept("collection_fees") { fetchTariff() }
    private val payouts = Kept("payout_fees") { fetchPayoutBands() }

    override suspend fun collectionFee(provider: String, amount: FiatAmount): ProviderFee =
        fee(provider, amount, collections) { bands, shillings -> bands.covering(shillings)?.feeFrom }

    override suspend fun payoutFee(provider: String, amount: FiatAmount): ProviderFee =
        fee(provider, amount, payouts) { bands, shillings -> bands.covering(shillings)?.let { slide(it, shillings) } }

    override suspend fun table(provider: String, kind: FeeKind): ProviderFeeTable? {
        if (provider != CollectionProviders.PAYHERO) return null
        val (table, source) = (if (kind == FeeKind.COLLECTION) collections else payouts).read() ?: return null
        return ProviderFeeTable(
            bands =
                table.bands.map {
                    ProviderFeeBand(minor(it.from), minor(it.to), minor(it.feeFrom), minor(it.feeTo))
                },
            source = source,
            asOf = table.fetchedAt,
        )
    }

    private fun minor(shillings: BigDecimal): Long =
        shillings.movePointRight(FiatAmount.MINOR_DIGITS).setScale(0, RoundingMode.CEILING).longValueExact()

    private suspend fun fee(
        provider: String,
        amount: FiatAmount,
        table: Kept,
        price: (List<Band>, BigDecimal) -> BigDecimal?,
    ): ProviderFee {
        if (provider != CollectionProviders.PAYHERO || amount.currency != KES || amount.amountMinor <= 0) {
            return ProviderFee.UNKNOWN
        }
        val (bands, source) = table.read() ?: return ProviderFee.UNKNOWN
        val shillings = BigDecimal.valueOf(amount.amountMinor, FiatAmount.MINOR_DIGITS)
        val charged = price(bands.bands, shillings) ?: return ProviderFee(null, FeeSource.UNKNOWN, bands.fetchedAt)
        val minor = charged.movePointRight(FiatAmount.MINOR_DIGITS).setScale(0, RoundingMode.CEILING).longValueExact()
        return ProviderFee(FiatAmount(minor, KES), source, bands.fetchedAt)
    }

    /**
     * The band's fee at [shillings]: a straight line from its first amount's fee to its last amount's, rounded up to
     * the cent. One exact division, so a fee that lands on a cent is never pushed to the next by rounding on the way.
     */
    private fun slide(band: Band, shillings: BigDecimal): BigDecimal {
        val width = band.to - band.from
        if (width.signum() == 0) return band.feeTo
        val along = shillings.min(band.to) - band.from
        val scaled = band.feeFrom * width + (band.feeTo - band.feeFrom) * along
        return scaled.divide(width, FiatAmount.MINOR_DIGITS, RoundingMode.CEILING)
    }

    /** Bands are whole shillings, both ends included: an amount with cents belongs to the band of its shillings. */
    private fun List<Band>.covering(shillings: BigDecimal): Band? =
        lastOrNull { it.from <= shillings }?.takeIf { shillings < it.to + BigDecimal.ONE }

    private suspend fun fetchTariff(): List<Band>? {
        for (host in tariffHosts) {
            val rows =
                getJson(host, TARIFF_PATH, "transaction_fees")?.let { text ->
                    runCatching { payHeroJson.decodeFromString(ListSerializer(TariffRow.serializer()), text) }
                        .getOrNull()
                }
            val bands = rows.orEmpty().mapNotNull { band(it.amountFrom, it.amountTo, it.price, it.price) }.cleaned()
            if (bands.isNotEmpty()) return bands
        }
        return null
    }

    private suspend fun fetchPayoutBands(): List<Band>? {
        val text = getJson(pricing, PRICING_PATH, "pricing") ?: return null
        val reply = runCatching { payHeroJson.decodeFromString(PricingReply.serializer(), text) }.getOrNull()
        if (reply == null || !reply.currency.equals(KES, ignoreCase = true)) return null
        val phone = reply.payouts.firstOrNull { it.recipient.equals(PHONE, ignoreCase = true) } ?: return null
        return phone.bands
            .mapNotNull { band(it.amountFrom, it.amountTo, it.feeFrom, it.feeTo) }
            .cleaned()
            .ifEmpty { null }
    }

    private suspend fun getJson(
        client: HttpClient,
        path: String,
        operation: String,
    ): String? =
        when (val answer = log.ask(operation) { client.get(path) { operation(operation) } }) {
            is Answer.Replied -> answer.body.takeIf { answer.status in SUCCESS }
            else -> null
        }

    /** A row as a band, or null when it is missing a number or is upside down (the tariff has one such row). */
    private fun band(
        from: String?,
        to: String?,
        feeFrom: String?,
        feeTo: String?,
    ): Band? {
        val numbers = listOf(from, to, feeFrom, feeTo).map { it?.trim()?.toBigDecimalOrNull() ?: return null }
        val (first, last, feeAtFirst, feeAtLast) = numbers
        if (first > last || feeAtFirst.signum() < 0 || feeAtLast.signum() < 0) return null
        return Band(first, last, feeAtFirst, feeAtLast)
    }

    /** In order of amount, one band per starting amount (the first one given wins). */
    private fun List<Band>.cleaned(): List<Band> = distinctBy { it.from }.sortedBy { it.from }

    private class Band(
        val from: BigDecimal,
        val to: BigDecimal,
        val feeFrom: BigDecimal,
        val feeTo: BigDecimal,
    )

    private class Table(val bands: List<Band>, val fetchedAt: Instant)

    /** One table and its refresh, as the class comment describes. One fetch at a time; the others wait for it. */
    private inner class Kept(private val name: String, private val fetch: suspend () -> List<Band>?) {
        private val refresh = Mutex()

        @Volatile
        private var table: Table? = null

        @Volatile
        private var failedAt: Instant? = null

        suspend fun read(): Pair<Table, FeeSource>? {
            fresh()?.let { return it to FeeSource.CACHED }
            return refresh.withLock {
                fresh()?.let { return@withLock it to FeeSource.CACHED }
                val now = clock.instant()
                if (failedAt?.let { Duration.between(it, now) < RETRY_AFTER } == true) return@withLock stale(now)
                val fetched = fetch()?.let { Table(it, now) }
                if (fetched == null) {
                    failedAt = now
                    log.warn("payhero fee table not read: table={} keptCopy={}", name, table != null)
                    return@withLock stale(now)
                }
                table = fetched
                failedAt = null
                log.info("payhero fee table read: table={} bands={}", name, fetched.bands.size)
                fetched to FeeSource.LIVE_TABLE
            }
        }

        private fun fresh(): Table? = table?.takeIf { Duration.between(it.fetchedAt, clock.instant()) < REFRESH_EVERY }

        private fun stale(now: Instant): Pair<Table, FeeSource>? =
            table?.takeIf { Duration.between(it.fetchedAt, now) < KEEP_FOR }?.let { it to FeeSource.CACHED }
    }

    companion object {
        const val PRICING_URL = "https://api.payhero.africa"
        const val TARIFF_PATH = "/api/transaction_fees"
        const val PRICING_PATH = "/api/pricing"

        val REFRESH_EVERY: Duration = Duration.ofHours(6)
        val KEEP_FOR: Duration = Duration.ofDays(7)
        val RETRY_AFTER: Duration = Duration.ofMinutes(5)

        private const val KES = "KES"
        private const val PHONE = "phone"
        private val SUCCESS = 200..299
    }
}

/** A row of `GET /api/transaction_fees`. Numbers are read as text and parsed exactly. */
@Serializable
internal data class TariffRow(
    @SerialName("amount_from") val amountFrom: String? = null,
    @SerialName("amount_to") val amountTo: String? = null,
    val price: String? = null,
)

/** `GET /api/pricing`: only the KES payout bands are read. */
@Serializable
internal data class PricingReply(val currency: String? = null, val payouts: List<PayoutPricing> = emptyList())

@Serializable
internal data class PayoutPricing(val recipient: String? = null, val bands: List<PricingBand> = emptyList())

@Serializable
internal data class PricingBand(
    @SerialName("amount_from") val amountFrom: String? = null,
    @SerialName("amount_to") val amountTo: String? = null,
    @SerialName("fee_from") val feeFrom: String? = null,
    @SerialName("fee_to") val feeTo: String? = null,
)
