package com.circaso.integrations.api

import com.circaso.shared.FiatAmount
import java.time.Instant

/**
 * What a collections provider itself charges the merchant for one transaction, read from the provider's **published**
 * tariff: no provider here returns its fee on a payment or a payout, so the public table is the only source.
 *
 * It never invents a fee. When the table can't be read, or the amount is outside it, the answer is
 * [FeeSource.UNKNOWN] with no fee, and the caller falls back to the table staff set in the admin.
 */
interface ProviderFees {
    /** The provider's charge for collecting [amount] (an M-Pesa prompt paid into the merchant's own channel). */
    suspend fun collectionFee(provider: String, amount: FiatAmount): ProviderFee

    /** The provider's charge for paying [amount] out to a phone. */
    suspend fun payoutFee(provider: String, amount: FiatAmount): ProviderFee

    /**
     * The provider's whole table for [kind], as this server holds it (fetched first if it has none), for staff to see
     * beside their fallback table (2026-11-24). Null when the provider publishes none or it can't be read.
     */
    suspend fun table(provider: String, kind: FeeKind): ProviderFeeTable? = null
}

/** Which of the provider's two tables. */
enum class FeeKind { COLLECTION, PAYOUT }

/**
 * One of the provider's published tables: [bands] in order of amount, with where it came from and when it was
 * fetched. For a band whose fee changes across it (payouts), [ProviderFeeBand.feeFromMinor] is the fee at its first
 * amount and [ProviderFeeBand.feeToMinor] at its last; for a flat band (collections) they are equal.
 */
data class ProviderFeeTable(val bands: List<ProviderFeeBand>, val source: FeeSource, val asOf: Instant)

/** KES minor units throughout; both ends whole shillings and included. */
data class ProviderFeeBand(
    val fromMinor: Long,
    val toMinor: Long,
    val feeFromMinor: Long,
    val feeToMinor: Long,
)

/**
 * A fee and where it came from. [fee] is null exactly when [source] is [FeeSource.UNKNOWN]; [asOf] is when the table
 * it was read from was fetched from the provider.
 */
data class ProviderFee(val fee: FiatAmount?, val source: FeeSource, val asOf: Instant? = null) {
    companion object {
        val UNKNOWN = ProviderFee(null, FeeSource.UNKNOWN)
    }
}

enum class FeeSource {
    /** From the provider's table, fetched for this very call. */
    LIVE_TABLE,

    /** From the copy of the provider's table this server keeps (see [ProviderFee.asOf] for its age). */
    CACHED,

    /** No fee: the table couldn't be read, is too old to trust, or doesn't cover the amount or the provider. */
    UNKNOWN,
}
