package com.circaso.wallet

import com.circaso.shared.Asset
import com.circaso.wallet.api.WalletId
import com.circaso.wallet.api.WalletOwnerType
import com.circaso.wallet.domain.models.FeeKind
import com.circaso.wallet.domain.models.FeeParties
import com.circaso.wallet.domain.models.FeeRule
import com.circaso.wallet.domain.models.GasSource
import com.circaso.wallet.domain.models.NoFeeReason
import com.circaso.wallet.domain.support.NetworkCosts
import com.circaso.wallet.domain.support.Pricing
import java.math.BigInteger
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The gas-inclusive fee (decision 2026-11-08) on its own: `ceil_cent(gas × (1 + margin)) + half_up_cent(rate ×
 * min(amount, cap))`, raised to `min_fee_usd` when set; nothing without a network cost; and the most that fits a
 * budget with its fee. All in micro-dollars (USDC's minor units).
 */
class PricingTest {
    private val usdc = 1_000_000L
    private val parties =
        FeeParties(
            FeeKind.INTERNAL,
            WalletOwnerType.PERSON,
            WalletOwnerType.PERSON,
            "BASE-SEPOLIA",
            Asset.USDC,
            WalletId.new(),
        )

    /** The INTERNAL person-to-person rule on sandbox today: 5 bps, cap 100 dollars, 20 % margin. */
    private fun rule(
        rateBps: Int = 5,
        capUsd: Long = 100,
        minFee: Long? = null,
        marginBps: Int = 2_000,
    ) = FeeRule(
        ruleId = UUID.randomUUID(),
        version = 3,
        kind = FeeKind.INTERNAL,
        senderType = null,
        receiverType = null,
        blockchain = null,
        asset = null,
        walletId = null,
        rateBps = rateBps,
        capBaseMinor = capUsd * usdc,
        minFeeMinor = minFee,
        gasShareMaxBps = 2_500,
        gasMarginBps = marginBps,
        effectiveFrom = Instant.EPOCH,
        effectiveTo = null,
    )

    private fun pricing(
        rule: FeeRule? = rule(),
        gas: Long? = 8_311,
        source: GasSource? = GasSource.RECENT,
    ) = Pricing(parties, rule, gas, "0xfee", null, gasSource = source)

    private fun fee(amountUsd: Double, pricing: Pricing = pricing()): Long =
        pricing.feeFor((amountUsd * usdc).toLong()).amountMinor.toLong()

    @Test
    fun `the network cost with its margin, up to the cent, plus the rate, half up to the cent`() {
        // 8 311 × 1.2 = 9 973.2 → 1 cent; 5 bps of 7.70 = 0.00385 → 0.
        assertEquals(10_000, fee(7.70))
        // 5 bps of 10 = 0.005 → half up 1 cent.
        assertEquals(20_000, fee(10.0))
        assertEquals(60_000, fee(100.0))
        val priced = pricing().feeFor(7_700_000)
        assertFalse(priced.minimum)
        assertEquals(5, priced.rateBps)
        assertEquals(8_311L, priced.gasMinor, "the cost before the margin, for the ledger")
        assertEquals(GasSource.RECENT, priced.gasSource)
    }

    @Test
    fun `Circle's estimate prices it the same way when that is the source`() {
        val estimated = pricing(gas = 17_748, source = GasSource.ESTIMATE)
        // 17 748 × 1.2 = 21 297.6 → 3 cents.
        assertEquals(30_000, fee(1.0, estimated))
        assertEquals(40_000, fee(10.0, estimated))
        assertEquals(80_000, fee(1_000.0, estimated))
        assertEquals(GasSource.ESTIMATE, estimated.feeFor(usdc).gasSource)
    }

    @Test
    fun `the rate stops at the cap, the network cost does not`() {
        assertEquals(60_000, fee(100.0))
        assertEquals(60_000, fee(1_000.0), "5 bps of at most 100 dollars")
        assertEquals(60_000, fee(1_000_000.0))
    }

    @Test
    fun `min_fee_usd raises it, and only then is it a minimum`() {
        val floored = pricing(rule(minFee = 50_000))
        assertEquals(50_000, fee(1.0, floored))
        assertTrue(floored.feeFor(usdc).minimum)
        val above = floored.feeFor(1_000 * usdc)
        assertEquals(60_000, above.amountMinor.toLong(), "1 cent of gas + 5 cents of rate is above the floor")
        assertFalse(above.minimum)
        // A floor that isn't a whole cent is raised to one.
        assertEquals(50_000, fee(1.0, pricing(rule(minFee = 40_001))))
    }

    @Test
    fun `a 0 percent rule charges the network cost alone`() {
        val promotion = pricing(rule(rateBps = 0))
        assertEquals(10_000, fee(1.0, promotion))
        assertEquals(10_000, fee(10_000.0, promotion))
        assertEquals(0, promotion.feeFor(usdc).rateBps)
        assertEquals(0, fee(5.0, pricing(rule(rateBps = 0), gas = 0)), "no network cost, nothing")
    }

    @Test
    fun `no network cost estimated - nothing is charged, as before`() {
        val none = pricing(gas = null, source = null)
        val free = none.feeFor(100 * usdc)
        assertEquals(BigInteger.ZERO, free.amountMinor)
        assertNull(free.feeAddress)
        assertFalse(free.charged)

        val solana = Pricing(parties, rule(), null, null, NoFeeReason.NO_SPLIT)
        assertEquals(BigInteger.ZERO, solana.feeFor(100 * usdc).amountMinor)
        assertEquals(NoFeeReason.NO_SPLIT, solana.feeFor(100 * usdc).reason)

        assertEquals(
            BigInteger.ZERO,
            Pricing(parties, null, 8_311, "0xfee", NoFeeReason.NO_RULE).feeFor(usdc).amountMinor,
        )
    }

    @Test
    fun `the margin is exact - a cost that lands on a cent stays there`() {
        // 25 000 × 1.2 = 30 000 exactly: 3 cents, not 4.
        assertEquals(30_000, fee(1.0, pricing(rule(rateBps = 0), gas = 25_000)))
        // 25 001 × 1.2 = 30 001.2: up to 4 cents.
        assertEquals(40_000, fee(1.0, pricing(rule(rateBps = 0), gas = 25_001)))
        assertEquals(10_000, fee(1.0, pricing(rule(rateBps = 0, marginBps = 0), gas = 10_000)))
    }

    @Test
    fun `the most that fits a budget with its fee is the largest such amount`() {
        val pricing = pricing(rule(rateBps = 50, capUsd = 10_000))
        listOf(50_000L, 1_000_000L, 7_700_000L, 100_000_000L, 123_456_789L).forEach { budget ->
            val max = pricing.maxAmount(budget)
            assertTrue(max + pricing.feeFor(max).amountMinor.toLong() <= budget, "fits $budget")
            assertTrue(
                max + 1 + pricing.feeFor(max + 1).amountMinor.toLong() > budget,
                "one more doesn't fit $budget",
            )
        }
        assertEquals(0, pricing.maxAmount(10_000), "a budget under the network cost sends nothing")
        // 100 dollars: x + 1 cent + 0.5 % of x ≤ 100 → 99.49 (fee 1 + 50 cents); 99.50 would need 1.51.
        assertEquals(99_490_000, pricing.maxAmount(100 * usdc))
        // Within a cap on one payment, and what is left of the window.
        assertEquals(20 * usdc, pricing.maxAmount(BigInteger.valueOf(100 * usdc), capUsdMinor = 20 * usdc).toLong())
        val left = pricing.maxAmount(BigInteger.valueOf(100 * usdc), leftUsdMinor = 10 * usdc).toLong()
        assertTrue(left + pricing.feeFor(left).usdMinor <= 10 * usdc)
        assertTrue(left + 1 + pricing.feeFor(left + 1).usdMinor > 10 * usdc)
    }

    @Test
    fun `the recent cost is the 75th percentile by nearest rank`() {
        val sandbox = listOf(7_619L, 7_619, 7_619, 7_619, 7_619, 7_619, 7_619, 8_311, 9_256, 14_379)
        assertEquals(8_311, NetworkCosts.percentile75(sandbox))
        assertEquals(9_256, NetworkCosts.percentile75(listOf(7_619, 7_619, 8_311, 9_256, 14_379)))
        assertEquals(5, NetworkCosts.percentile75(listOf(5)))
    }
}
