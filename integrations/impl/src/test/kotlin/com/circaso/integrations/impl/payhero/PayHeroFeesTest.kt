package com.circaso.integrations.impl.payhero

import com.circaso.integrations.api.FeeSource
import com.circaso.integrations.api.ProviderFee
import com.circaso.integrations.impl.payhero.FakePayHero.Companion.API
import com.circaso.integrations.impl.payhero.FakePayHero.Companion.LEGACY
import com.circaso.integrations.impl.payhero.FakePayHero.Companion.PRICING
import com.circaso.shared.FiatAmount
import kotlinx.coroutines.runBlocking
import java.net.ConnectException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PayHeroFeesTest {
    private val vendor = FakePayHero()
    private var now = Instant.parse("2026-11-23T12:00:00Z")
    private val clock =
        object : Clock() {
            override fun instant(): Instant = now

            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId?): Clock = this
        }
    private val fees =
        PayHeroFees(
            hosts = vendor.hosts,
            pricing = vendor.clients.named("payhero-pricing") { baseUrl = PayHeroFees.PRICING_URL },
            clock = clock,
        )

    private val legacyTariff = "$LEGACY/api/transaction_fees"
    private val apiTariff = "$API/api/transaction_fees"
    private val pricing = "$PRICING/api/pricing"

    /** The live table of 2026-11-23, with its upside-down row (35000 to 34999) left in. */
    private val tariff =
        """[{"amount_from":1,"amount_to":10,"price":0},{"amount_from":11,"amount_to":49,"price":1},""" +
            """{"amount_from":50,"amount_to":499,"price":6},{"amount_from":500,"amount_to":999,"price":10},""" +
            """{"amount_from":20000,"amount_to":34999,"price":80},""" +
            """{"amount_from":35000,"amount_to":34999,"price":80},""" +
            """{"amount_from":35000,"amount_to":49999,"price":105},""" +
            """{"amount_from":750000,"amount_to":999999,"price":320}]"""

    private val payoutPricing =
        """{"currency":"KES","collections":[{"channel":"momo","rate":0.008}],"payouts":[{"recipient":"phone",""" +
            """"bands":[{"amount_from":1,"amount_to":100,"fee_from":0.01,"fee_to":0.8},""" +
            """{"amount_from":101,"amount_to":500,"fee_from":7.81,"fee_to":11},""" +
            """{"amount_from":501,"amount_to":1000,"fee_from":15.01,"fee_to":19},""" +
            """{"amount_from":20001,"amount_to":250000,"fee_from":199.01,"fee_to":2039}]},""" +
            """{"recipient":"bank","bands":[{"amount_from":1,"amount_to":100,"fee_from":50,"fee_to":50}]}],""" +
            """"global":[{"country_code":"UG","operation":"payout","channel":"momo","rate":0.02}]}"""

    private fun kes(shillings: Long) = FiatAmount.ofWholeUnits(shillings, "KES")

    private fun collection(amount: FiatAmount, provider: String = "payhero"): ProviderFee =
        runBlocking { fees.collectionFee(provider, amount) }

    private fun payout(amount: FiatAmount, provider: String = "payhero"): ProviderFee =
        runBlocking { fees.payoutFee(provider, amount) }

    @Test
    fun `a collection's fee is its band's price, from the host the prompt goes to`() {
        vendor.answer(legacyTariff, tariff)

        assertEquals(ProviderFee(FiatAmount(0, "KES"), FeeSource.LIVE_TABLE, now), collection(kes(10)))
        assertEquals(
            ProviderFee(FiatAmount(100, "KES"), FeeSource.CACHED, now),
            collection(kes(11)),
            "kept after one read",
        )
        assertEquals(FiatAmount(600, "KES"), collection(kes(499)).fee)
        assertEquals(FiatAmount(1_000, "KES"), collection(kes(500)).fee)
        assertEquals(
            FiatAmount(600, "KES"),
            collection(FiatAmount(49_950, "KES")).fee,
            "cents belong to their shilling",
        )
        assertEquals(listOf(legacyTariff), vendor.asked, "one fetch serves them all")
    }

    @Test
    fun `the tariff's upside-down row is dropped and the good row for that amount is used`() {
        vendor.answer(legacyTariff, tariff)

        assertEquals(FiatAmount(8_000, "KES"), collection(kes(34_999)).fee)
        assertEquals(FiatAmount(10_500, "KES"), collection(kes(35_000)).fee, "not the malformed row's 80")
        assertEquals(FiatAmount(10_500, "KES"), collection(kes(49_999)).fee)
    }

    @Test
    fun `an amount no band covers has no fee, and neither has another provider or currency`() {
        vendor.answer(legacyTariff, tariff)
        vendor.answer(pricing, payoutPricing)

        assertEquals(ProviderFee(null, FeeSource.UNKNOWN, now), collection(kes(1_000)), "a gap in the table")
        assertEquals(ProviderFee(null, FeeSource.UNKNOWN, now), collection(kes(1_000_000)), "above the last band")
        assertEquals(ProviderFee(null, FeeSource.UNKNOWN, now), payout(kes(5_000)))
        assertEquals(ProviderFee.UNKNOWN, collection(kes(100), provider = "log"))
        assertEquals(ProviderFee.UNKNOWN, payout(FiatAmount.ofWholeUnits(100, "UGX")))
        assertEquals(ProviderFee.UNKNOWN, payout(FiatAmount(0, "KES")))
    }

    @Test
    fun `the new host's tariff is used when the legacy host gives none`() {
        vendor.answer(legacyTariff, """{"error_message":"gone"}""", 500)
        vendor.answer(
            apiTariff,
            """[{"amount_from":"1","amount_to":"49","price":"0"},{"amount_from":50,"amount_to":499,"price":6}]""",
        )

        assertEquals(ProviderFee(FiatAmount(0, "KES"), FeeSource.LIVE_TABLE, now), collection(kes(30)))
        assertEquals(listOf(legacyTariff, apiTariff), vendor.asked)
    }

    @Test
    fun `a payout's fee slides in a straight line across its band and is rounded up to the cent`() {
        vendor.answer(pricing, payoutPricing)

        assertEquals(ProviderFee(FiatAmount(1, "KES"), FeeSource.LIVE_TABLE, now), payout(kes(1)), "the band's start")
        assertEquals(FiatAmount(80, "KES"), payout(kes(100)).fee, "the band's end")
        // KES 10: 0.01 + (10 - 1) / (100 - 1) x (0.80 - 0.01) = 0.0818…, rounded up to 0.09
        assertEquals(FiatAmount(9, "KES"), payout(kes(10)).fee)
        assertEquals(FiatAmount(781, "KES"), payout(kes(101)).fee)
        // KES 300: 7.81 + 199 / 399 x 3.19 = 9.4010…, rounded up to 9.41
        assertEquals(FiatAmount(941, "KES"), payout(kes(300)).fee)
        assertEquals(FiatAmount(1_100, "KES"), payout(kes(500)).fee, "a fee on a cent stays on it")
        assertEquals(FiatAmount(1_900, "KES"), payout(kes(1_000)).fee)
        assertEquals(FiatAmount(203_900, "KES"), payout(kes(250_000)).fee)
        assertEquals(listOf(pricing), vendor.asked, "the phone bands only, fetched once")
    }

    @Test
    fun `no fee is invented when the tables can't be read`() {
        vendor.fail(legacyTariff) { ConnectException("refused") }
        vendor.answer(apiTariff, "<html>maintenance</html>")
        vendor.answer(pricing, """{"currency":"USD","payouts":[]}""")

        assertEquals(ProviderFee.UNKNOWN, collection(kes(100)))
        assertEquals(ProviderFee.UNKNOWN, payout(kes(100)))
        assertNull(payout(kes(100)).fee)

        val calls = vendor.asked.size
        assertEquals(ProviderFee.UNKNOWN, collection(kes(100)))
        assertEquals(calls, vendor.asked.size, "a failed fetch isn't tried again at once")

        now += PayHeroFees.RETRY_AFTER
        vendor.answer(legacyTariff, tariff)
        assertEquals(ProviderFee(FiatAmount(600, "KES"), FeeSource.LIVE_TABLE, now), collection(kes(100)))
    }

    @Test
    fun `a table is refreshed when it is old, kept while the refresh fails, and dropped when too old to trust`() {
        vendor.answer(pricing, payoutPricing)
        val fetchedAt = now
        assertEquals(FeeSource.LIVE_TABLE, payout(kes(100)).source)

        now += PayHeroFees.REFRESH_EVERY - Duration.ofSeconds(1)
        assertEquals(ProviderFee(FiatAmount(80, "KES"), FeeSource.CACHED, fetchedAt), payout(kes(100)))
        assertEquals(1, vendor.asked.size)

        now += Duration.ofSeconds(1)
        assertEquals(FeeSource.LIVE_TABLE, payout(kes(100)).source, "old enough: read again")
        val refreshedAt = now
        assertEquals(2, vendor.asked.size)

        vendor.answer(pricing, "", 503)
        now += PayHeroFees.REFRESH_EVERY
        assertEquals(
            ProviderFee(FiatAmount(80, "KES"), FeeSource.CACHED, refreshedAt),
            payout(kes(100)),
            "the kept copy",
        )

        now = refreshedAt + PayHeroFees.KEEP_FOR
        assertEquals(ProviderFee.UNKNOWN, payout(kes(100)), "a week old with no refresh: no longer trusted")
    }
}
