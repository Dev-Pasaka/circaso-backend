package com.circaso.ramp

import com.circaso.integrations.api.ExchangeRates
import com.circaso.integrations.api.UsdRate
import com.circaso.ramp.data.support.RatePricing
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.Priced
import com.circaso.ramp.domain.models.PricingSettings
import com.circaso.ramp.domain.models.RampFeeBand
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.support.PricingBook
import com.circaso.ramp.domain.support.RampFeeBands
import com.circaso.ramp.domain.support.SalePricing
import com.circaso.shared.Network
import com.circaso.shared.Resource
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The ramp fee's amount bands (decision 2026-11-24, "the band sets the rate"): the band an order's KES amount falls in
 * sets the fee rate for the whole amount. Buying by what the user pays; selling by the KES value of what is sold.
 */
class RampFeeBandsTest {
    private val clock = TestClock()
    private val testnet = Network.TESTNET
    private val rows = MemoryPricingSettings()
    private val book = PricingBook(rows, RecordingAlerts(), clock, Duration.ZERO)
    private val rates =
        object : ExchangeRates {
            override suspend fun usdRate(currency: String) = UsdRate("KES", BigDecimal("130"), clock.instant())
        }
    private val pricing = RatePricing(rates, book, clock)

    /** Below KES 1 000 2 %; KES 1 000 and up 1.5 %. */
    private val bands = listOf(RampFeeBand(0, 100_000, 200), RampFeeBand(100_000, null, 150))

    private suspend fun buy(fiatMinor: Long? = null, assetMinor: Long? = null): Priced {
        rows.seed(PricingSettings.defaults(testnet, clock.instant()))
        return (pricing.price(testnet, Direction.ON_RAMP, fiatMinor, assetMinor, bands) as Resource.Success).data
    }

    @Test
    fun `each amount takes the band it falls in, at and around the boundary`() {
        assertEquals(200, RampFeeBands.bandFor(bands, 0).feeBps)
        assertEquals(200, RampFeeBands.bandFor(bands, 99_999).feeBps)
        assertEquals(150, RampFeeBands.bandFor(bands, 100_000).feeBps, "a boundary belongs to the band it starts")
        assertEquals(150, RampFeeBands.bandFor(bands, 100_001).feeBps)
        assertEquals(150, RampFeeBands.bandFor(bands, 100_000_000).feeBps)
    }

    @Test
    fun `buying by shillings - the band of what is paid prices all of it`() =
        runTest {
            // KES 999 at 2 %: 19.98 → 20; 979 / 130 = 7.530769.
            val below = buy(fiatMinor = 99_900)
            assertEquals(2_000, below.feeMinor)
            assertEquals(7_530_769, below.assetMinor)
            assertEquals(200 to 0L, below.feeBps to below.feeBandFromMinor)
            // KES 1 000 at 1.5 %: 15; 985 / 130 = 7.576923. The cliff: one shilling more buys more.
            val at = buy(fiatMinor = 100_000)
            assertEquals(1_500, at.feeMinor)
            assertEquals(7_576_923, at.assetMinor)
            assertEquals(150 to 100_000L, at.feeBps to at.feeBandFromMinor)
        }

    @Test
    fun `buying by USDC lands in the band whose rate was used`() =
        runTest {
            // 5 USDC: at 2 % KES 664, inside the 2 % band.
            val small = buy(assetMinor = 5_000_000)
            assertEquals(66_400L to 200, small.fiatMinor to small.feeBps)
            assertEquals(5_000_000, small.assetMinor)

            // 10 USDC: at 2 % KES 1 327 isn't in the 2 % band; at 1.5 % KES 1 320 is in its own.
            val large = buy(assetMinor = 10_000_000)
            assertEquals(132_000L to 150, large.fiatMinor to large.feeBps)
            assertEquals(10_000_000, large.assetMinor)

            // 7.56 USDC is in the cliff: at 2 % KES 1 003 (the 1.5 % band), at 1.5 % KES 998 (the 2 % band). It is
            // priced at the start of the cheaper band, KES 1 000, which buys at least what was asked.
            val cliff = buy(assetMinor = 7_560_000)
            assertEquals(100_000L to 150, cliff.fiatMinor to cliff.feeBps)
            assertEquals(100_000L, cliff.feeBandFromMinor)
            assertEquals(7_576_923, cliff.assetMinor)
        }

    @Test
    fun `one band is exactly the flat rate, and records no band`() =
        runTest {
            rows.seed(PricingSettings.defaults(testnet, clock.instant()))
            val flat = listOf(RampFeeBand(0, null, 200))
            val priced = (pricing.price(testnet, Direction.ON_RAMP, 130_000, null, flat) as Resource.Success).data
            assertEquals(2_600, priced.feeMinor)
            assertEquals(9_800_000, priced.assetMinor)
            assertNull(priced.feeBandFromMinor)
        }

    @Test
    fun `selling - the band of what the USDC is worth sets the fee`() =
        runTest {
            val rate = BigDecimal("130")
            // 7 USDC is KES 910: 2 % is 18.20, KES 891 paid out.
            val small = SalePricing.ofAsset(7_000_000, rate, bands) { 0L }!!
            assertEquals(Triple(1_820L, 89_100L, 200), Triple(small.feeMinor, small.payoutMinor, small.feeBps))
            // 8 USDC is KES 1 040: 1.5 % is 15.60, KES 1 024 paid out.
            val large = SalePricing.ofAsset(8_000_000, rate, bands) { 0L }!!
            assertEquals(Triple(1_560L, 102_400L, 150), Triple(large.feeMinor, large.payoutMinor, large.feeBps))
            assertEquals(100_000L, large.feeBandFromMinor)

            // Receiving KES 980: at 2 % the sale is worth KES 1 000, in the 1.5 % band; at 1.5 % it is worth KES 994.92,
            // in the 2 % band. It is priced at the start of the 1.5 % band; the payout stays exact and the rounding goes
            // to the fee.
            val cliff = SalePricing.ofPayout(98_000, rate, bands) { 0L }!!
            assertEquals(98_000, cliff.payoutMinor)
            assertEquals(150, cliff.feeBps)
            assertEquals(7_692_308, cliff.assetMinor)
            assertEquals(SalePricing.grossOf(cliff.assetMinor, rate) - 98_000, cliff.feeMinor)
        }

    @Test
    fun `selling with one band is today's numbers`() =
        runTest {
            val rate = BigDecimal("130")
            val flat = listOf(RampFeeBand(0, null, 200))
            val sale = SalePricing.ofAsset(10_000_000, rate, flat) { 2_000L }!!
            // KES 1 300, fee 26, provider 20: KES 1 254.
            assertEquals(
                Triple(2_600L, 125_400L, 2_000L),
                Triple(sale.feeMinor, sale.payoutMinor, sale.providerFeeMinor),
            )
            assertNull(sale.feeBandFromMinor)
        }

    @Test
    fun `bad bands are refused by field`() {
        val field = "onRampFeeBands"
        assertNull(RampFeeBands.problem(emptyList(), field))
        assertNull(RampFeeBands.problem(bands, field))
        assertEquals(
            "onRampFeeBands[0].fromMinor" to "the first band starts at 0",
            RampFeeBands.problem(listOf(RampFeeBand(100, null, 100)), field),
        )
        assertEquals(
            "onRampFeeBands[1].fromMinor" to "must be where the band before ends",
            RampFeeBands.problem(listOf(RampFeeBand(0, 100_000, 200), RampFeeBand(120_000, null, 150)), field),
        )
        assertEquals(
            "onRampFeeBands[0].toMinor" to "the last band has no end",
            RampFeeBands.problem(listOf(RampFeeBand(0, 100_000, 200)), field),
        )
        assertEquals(
            "onRampFeeBands[0].toMinor" to "only the last band has no end",
            RampFeeBands.problem(listOf(RampFeeBand(0, null, 200), RampFeeBand(100, null, 150)), field),
        )
        assertEquals(
            "onRampFeeBands[1].feeBps" to "must be 0 to ${RampSettings.MAX_FEE_BPS}",
            RampFeeBands.problem(listOf(RampFeeBand(0, 100_000, 200), RampFeeBand(100_000, null, 501)), field),
        )
    }
}
