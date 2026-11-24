package com.circaso.ramp

import com.circaso.integrations.api.FeeSource
import com.circaso.integrations.api.ProviderFee
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.models.PayoutFee
import com.circaso.ramp.domain.models.QuoteCommand
import com.circaso.ramp.domain.models.StaffFeeBand
import com.circaso.ramp.domain.models.StaffFeeTables
import com.circaso.ramp.domain.support.ProviderFeeLookup
import com.circaso.ramp.domain.support.PurchaseTotals
import com.circaso.shared.FiatAmount
import com.circaso.shared.Resource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The M-Pesa providers' own fees passed to the customer (decision 2026-11-24): PayHero's per-prompt fee on a purchase
 * (the prompt asks for the merchant's quote plus that fee), with the fallback order LIVE → CACHED → STAFF_TABLE →
 * refuse.
 */
class ProviderFeesTest {
    private val ramp = Harness()

    private fun <T> Resource<T>.error() = (this as Resource.Error).error

    private fun <T> Resource<T>.data(): T = (this as Resource.Success).data

    /** PayHero's fee for a prompt of [minor], from its real table as seeded (whole shillings, KES minor units). */
    private fun payhero(minor: Long): Long? =
        StaffFeeTables.PAYHERO_COLLECTIONS.firstOrNull { minor <= it.upToMinor }?.feeMinor

    private val feeOf: suspend (Long) -> Pair<Long, String>? = { amount -> payhero(amount)?.let { it to "LIVE" } }

    private suspend fun forBase(shillings: Long) = PurchaseTotals.forBase(shillings * 100, feeOf)!!

    private suspend fun forTotal(shillings: Long) = PurchaseTotals.forTotal(shillings * 100, feeOf)!!

    // ---- the band, on PayHero's real table ----

    @Test
    fun `the seeded table is PayHero's, at its boundaries, without the upside-down row`() {
        assertEquals(0L, payhero(1_000), "KES 10")
        assertEquals(100L, payhero(1_100), "KES 11")
        assertEquals(100L, payhero(4_900), "KES 49")
        assertEquals(600L, payhero(5_000), "KES 50")
        assertEquals(600L, payhero(49_900), "KES 499")
        assertEquals(1_000L, payhero(50_000), "KES 500")
        assertEquals(8_000L, payhero(3_499_900), "KES 34 999")
        assertEquals(10_500L, payhero(3_500_000), "KES 35 000: the good row, not the bad row's 80")
        assertEquals(32_000L, payhero(99_999_900), "KES 999 999")
        assertNull(payhero(100_000_000), "above the table")
        val ups = StaffFeeTables.PAYHERO_COLLECTIONS.map { it.upToMinor }
        assertEquals(ups.sorted(), ups)
        assertEquals(ups.distinct(), ups)
    }

    // ---- the fixed point ----

    @Test
    fun `asked in USDC - the least total that nets the merchant their quote, across the 499 to 500 edge`() =
        runTest {
            assertEquals(
                Triple(10_600L, 600L, 10_000L),
                forBase(100).let {
                    Triple(it.totalMinor, it.providerFeeMinor, it.baseMinor)
                },
            )
            assertEquals(
                49_900L to 600L,
                forBase(493).let { it.totalMinor to it.providerFeeMinor },
                "KES 499 is still the 6 band",
            )
            // 494 + 6 = 500, which is the 10 band: 494 + 10 = 504, whose fee is 10 too.
            assertEquals(50_400L to 1_000L, forBase(494).let { it.totalMinor to it.providerFeeMinor })
            assertEquals(51_000L to 1_000L, forBase(500).let { it.totalMinor to it.providerFeeMinor })
            // 2 480 + 20 = 2 500, the 25 band: 2 505.
            assertEquals(250_500L to 2_500L, forBase(2_480).let { it.totalMinor to it.providerFeeMinor })
            assertEquals(252_500L to 2_500L, forBase(2_500).let { it.totalMinor to it.providerFeeMinor })
            assertEquals(
                1_000L to 0L,
                forBase(10).let {
                    it.totalMinor to it.providerFeeMinor
                },
                "PayHero charges nothing to KES 10",
            )
        }

    @Test
    fun `every base from 1 to 20 000 shillings - the total nets it exactly, and one shilling less would not`() =
        runTest {
            for (base in 1L..20_000L) {
                val total = forBase(base)
                val fee = payhero(total.totalMinor)!!
                assertEquals(base * 100, total.totalMinor - fee, "the merchant nets their quote at $base")
                assertEquals(fee, total.providerFeeMinor)
                val less = total.totalMinor - 100
                assertTrue(less - payhero(less)!! < base * 100, "the least total at $base")
            }
        }

    @Test
    fun `asked in shillings - what the user types is the total, and PayHero's fee for it comes out of it`() =
        runTest {
            assertEquals(
                Triple(10_000L, 600L, 9_400L),
                forTotal(100).let {
                    Triple(it.totalMinor, it.providerFeeMinor, it.baseMinor)
                },
            )
            assertEquals(
                Triple(49_900L, 600L, 49_300L),
                forTotal(499).let {
                    Triple(it.totalMinor, it.providerFeeMinor, it.baseMinor)
                },
            )
            assertEquals(
                Triple(50_000L, 1_000L, 49_000L),
                forTotal(500).let {
                    Triple(it.totalMinor, it.providerFeeMinor, it.baseMinor)
                },
            )
            assertEquals(
                Triple(250_000L, 2_500L, 247_500L),
                forTotal(2_500).let {
                    Triple(it.totalMinor, it.providerFeeMinor, it.baseMinor)
                },
            )
            assertNull(PurchaseTotals.forTotal(100) { 100L to "LIVE" }, "a fee that leaves nothing to convert")
        }

    @Test
    fun `a fee in cents is rounded up to the shilling, never short`() =
        runTest {
            val total = PurchaseTotals.forBase(10_000) { 250L to "STAFF_TABLE" }!!
            assertEquals(10_300L to 300L, total.totalMinor to total.providerFeeMinor)
        }

    // ---- the fallback order ----

    @Test
    fun `LIVE, then CACHED, then STAFF_TABLE, then nothing`() {
        val staff = listOf(StaffFeeBand(49_900, 600), StaffFeeBand(99_900, 1_000))
        val kes = { minor: Long -> FiatAmount(minor, "KES") }
        assertEquals(
            PayoutFee(700, "LIVE"),
            ProviderFeeLookup.of(ProviderFee(kes(700), FeeSource.LIVE_TABLE), staff, 10_000),
        )
        assertEquals(
            PayoutFee(700, "CACHED"),
            ProviderFeeLookup.of(ProviderFee(kes(700), FeeSource.CACHED), staff, 10_000),
        )
        assertEquals(PayoutFee(600, "STAFF_TABLE"), ProviderFeeLookup.of(ProviderFee.UNKNOWN, staff, 10_000))
        assertEquals(PayoutFee(1_000, "STAFF_TABLE"), ProviderFeeLookup.of(ProviderFee.UNKNOWN, staff, 50_000))
        assertNull(ProviderFeeLookup.of(ProviderFee.UNKNOWN, staff, 100_000), "above staff's last band")
        assertNull(ProviderFeeLookup.of(ProviderFee.UNKNOWN, emptyList(), 10_000), "no table at all")
        assertNull(
            ProviderFeeLookup.of(ProviderFee(FiatAmount(700, "UGX"), FeeSource.LIVE_TABLE), emptyList(), 10_000),
            "another currency is no fee",
        )
    }

    // ---- quotes, orders, the prompt and the merchant's earnings ----

    private fun payheroLive() {
        ramp.providerFees.collectionFee = { payhero(it) }
    }

    @Test
    fun `a purchase asked in shillings prompts exactly that, PayHero's fee inside it, the USDC on the rest`() =
        runTest {
            payheroLive()
            // KES 1 300: PayHero 15; the KES 1 285 left is priced as before (this harness: 2 %, 25.70, at 130).
            val quote = ramp.quote(shillings = 1_300).data()
            assertEquals(130_000L, quote.fiat.amountMinor)
            assertEquals(1_500L to "LIVE", quote.providerFeeMinor to quote.providerFeeSource)
            assertEquals(2_570L, quote.feeMinor, "Circaso's fee on the KES 1 285, not on the 1 300")
            assertEquals((128_500L - 2_570L) * 10_000 / 130, quote.assetMinor)

            val order = ramp.prompted()
            assertEquals(1_500L to "LIVE", order.providerFeeMinor to order.providerFeeSource)
            assertEquals(
                130_000L,
                ramp.collections.prompts
                    .last()
                    .amount.amountMinor,
                "the prompt asks for the total",
            )
        }

    @Test
    fun `a purchase asked in USDC adds PayHero's fee on top, and the USDC is what was asked`() =
        runTest {
            val ask = QuoteCommand(ramp.amina, TESTNET, Direction.ON_RAMP, CHAIN, null, 10_000_000)
            val without = ramp.createQuote(ask).data()
            payheroLive()
            val quote = ramp.createQuote(ask).data()
            // The merchant's quote for 10 USDC is KES 1 300 in this harness; PayHero's fee for 1 315 is 15.
            assertEquals(130_000L, without.fiat.amountMinor)
            assertEquals(131_500L to 1_500L, quote.fiat.amountMinor to quote.providerFeeMinor)
            assertEquals(
                without.assetMinor to without.feeMinor,
                quote.assetMinor to quote.feeMinor,
                "the USDC and Circaso's fee are untouched",
            )
        }

    @Test
    fun `PayHero's table unreadable - staff's table prices it, and with neither nothing is quoted`() =
        runTest {
            ramp.providerFees.collectionSource = FeeSource.UNKNOWN
            val staff = ramp.quote(shillings = 1_300).data()
            assertEquals(1_500L to "STAFF_TABLE", staff.providerFeeMinor to staff.providerFeeSource)

            ramp.settingsRows.set(TESTNET) { it.copy(collectionFeeBands = emptyList()) }
            ramp.book.invalidate()
            assertEquals("ramp.pricing_unavailable", ramp.quote(shillings = 1_300).error().code, "never a fee of 0")
            assertEquals(
                "ramp.pricing_unavailable",
                ramp
                    .createQuote(
                        QuoteCommand(ramp.amina, TESTNET, Direction.ON_RAMP, CHAIN, null, 9_800_000),
                    ).error()
                    .code,
            )
        }

    @Test
    fun `the order limits are on the total prompted`() =
        runTest {
            payheroLive()
            ramp.settingsRows.set(TESTNET) { it.copy(orderMaxMinor = 130_000) }
            ramp.book.invalidate()
            assertEquals(
                130_000L,
                ramp
                    .quote(shillings = 1_300)
                    .data()
                    .fiat.amountMinor,
                "the total at the limit",
            )
            // 10 USDC needs KES 1 300 for the merchant plus PayHero's 15: over the limit.
            val over = ramp.createQuote(QuoteCommand(ramp.amina, TESTNET, Direction.ON_RAMP, CHAIN, null, 10_000_000))
            assertEquals("ramp.limit_exceeded", over.error().code)
        }

    @Test
    fun `the merchant's earnings never count PayHero's fee - it passes through`() =
        runTest {
            payheroLive()
            val order = ramp.completed()
            assertEquals(OrderState.COMPLETED, order.state)
            val earned =
                ramp.ledger.commissions
                    .getValue(order.orderId)
                    .split
            // Circaso's fee alone (25.70) is split 70/30: the 15 PayHero takes is nobody's income.
            assertEquals(2_570L, order.feeMinor)
            assertEquals(order.feeMinor, earned.merchantMinor + earned.platformMinor)
            assertEquals(1_500L, order.providerFeeMinor)
            assertEquals(130_000L, order.fiat.amountMinor, "the till received the total")
        }
}
