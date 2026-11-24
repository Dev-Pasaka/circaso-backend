package com.circaso.wallet

import com.circaso.shared.Asset
import com.circaso.shared.Resource
import com.circaso.wallet.api.FeeRuleBand
import com.circaso.wallet.api.FeeRuleTerms
import com.circaso.wallet.api.WalletId
import com.circaso.wallet.api.WalletOwnerType
import com.circaso.wallet.domain.models.FeeBand
import com.circaso.wallet.domain.models.FeeKind
import com.circaso.wallet.domain.models.FeeParties
import com.circaso.wallet.domain.models.FeeRule
import com.circaso.wallet.domain.models.GasSource
import com.circaso.wallet.domain.support.Pricing
import com.circaso.wallet.domain.support.RuleChecks
import java.math.BigInteger
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Amount bands on a fee rule (decision 2026-11-24, "the band sets the rate"): the band the payment's dollar amount
 * falls in sets the rate for the whole amount. The network cost, the cap base and the minimum work as before; one band
 * is exactly the flat rate.
 */
class FeeBandsTest {
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

    /** $0–1 2 %, $1–5 1.5 %, $5–100 1 %, $100 and up 0.5 % (the owner's example). */
    private val bands =
        listOf(
            FeeBand(0, usdc, 200),
            FeeBand(usdc, 5 * usdc, 150),
            FeeBand(5 * usdc, 100 * usdc, 100),
            FeeBand(100 * usdc, null, 50),
        )

    private fun rule(
        rateBps: Int = 200,
        bands: List<FeeBand> = this.bands,
        capUsd: Long = 10_000,
        minFee: Long? = null,
    ) = FeeRule(
        ruleId = UUID.randomUUID(),
        version = 1,
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
        gasMarginBps = 2_000,
        effectiveFrom = Instant.EPOCH,
        effectiveTo = null,
        bands = bands,
    )

    /** 8 311 micro-dollars of gas × 1.2 = 1 cent of network cost on every payment. */
    private fun pricing(rule: FeeRule = rule()) =
        Pricing(parties, rule, 8_311, "0xfee", null, gasSource = GasSource.RECENT)

    @Test
    fun `each amount takes the band it falls in, at and around every boundary`() {
        val rule = rule()
        assertEquals(200, rule.bandFor(0).rateBps)
        assertEquals(200, rule.bandFor(usdc - 1).rateBps, "just below \$1")
        assertEquals(150, rule.bandFor(usdc).rateBps, "a boundary belongs to the band it starts")
        assertEquals(150, rule.bandFor(usdc + 1).rateBps)
        assertEquals(150, rule.bandFor(5 * usdc - 1).rateBps)
        assertEquals(100, rule.bandFor(5 * usdc).rateBps)
        assertEquals(100, rule.bandFor(100 * usdc - 1).rateBps)
        assertEquals(50, rule.bandFor(100 * usdc).rateBps)
        assertEquals(50, rule.bandFor(1_000_000 * usdc).rateBps, "the last band has no end")
    }

    @Test
    fun `the band's rate prices the whole amount, with the network cost on top`() {
        val pricing = pricing()
        // 2 % of 0.999999 = 1.999998 cents → 2; + 1 cent network.
        assertEquals(30_000, pricing.feeFor(usdc - 1).amountMinor.toLong())
        // 1.5 % of 1.00 = 1.5 cents → half up 2.
        assertEquals(30_000, pricing.feeFor(usdc).amountMinor.toLong())
        // 1.5 % of 4.99 = 7.485 cents → 7; then the cliff: 1 % of 5.00 = 5 cents.
        assertEquals(80_000, pricing.feeFor(4_990_000).amountMinor.toLong())
        assertEquals(60_000, pricing.feeFor(5 * usdc).amountMinor.toLong())
        // 1 % of 99.99 = 99.99 cents → 1.00; 0.5 % of 100 = 0.50.
        assertEquals(1_010_000, pricing.feeFor(99_990_000).amountMinor.toLong())
        assertEquals(510_000, pricing.feeFor(100 * usdc).amountMinor.toLong())

        val fee = pricing.feeFor(5 * usdc)
        assertEquals(100, fee.rateBps, "the quote shows the band's rate")
        assertEquals(5 * usdc, fee.bandFromMinor, "and records which band")
    }

    @Test
    fun `the cap base and the minimum still apply within a band`() {
        // 0.5 % of at most 1 000 dollars: 5 dollars + 1 cent, however large.
        val capped = pricing(rule(capUsd = 1_000))
        assertEquals(5_010_000, capped.feeFor(50_000 * usdc).amountMinor.toLong())
        val floored = pricing(rule(minFee = 50_000))
        assertEquals(50_000, floored.feeFor(usdc).amountMinor.toLong())
        assertTrue(floored.feeFor(usdc).minimum)
    }

    @Test
    fun `one band from 0 with no end is exactly the flat rate`() {
        val flat = pricing(rule(rateBps = 5, bands = emptyList()))
        val oneBand = pricing(rule(rateBps = 5, bands = listOf(FeeBand(0, null, 5))))
        listOf(0L, 1L, 999_999L, usdc, 7_700_000L, 10 * usdc, 100 * usdc, 123_456_789L, 10_000_000 * usdc).forEach {
            assertEquals(flat.feeFor(it).amountMinor, oneBand.feeFor(it).amountMinor, "at $it")
        }
        assertNull(flat.feeFor(usdc).bandFromMinor, "a flat rule records no band")
        assertEquals(99_940_000, flat.maxAmount(100 * usdc))
    }

    @Test
    fun `the most that fits a budget looks past a cliff into the cheaper band`() {
        val pricing = pricing()
        // 5.07 dollars: in the 1.5 % band 4.99 + 0.08 fits, but in the 1 % band 5.01 + 0.06 fits too, and is more.
        val max = pricing.maxAmount(BigInteger.valueOf(5_070_000)).toLong()
        assertEquals(5_010_000, max)
        assertTrue(max + pricing.feeFor(max).amountMinor.toLong() <= 5_070_000)
        listOf(50_000L, 1_000_000L, 7_700_000L, 100_000_000L, 123_456_789L).forEach { budget ->
            val most = pricing.maxAmount(budget)
            assertTrue(most + pricing.feeFor(most).amountMinor.toLong() <= budget, "fits $budget")
        }
        assertEquals(0, pricing.maxAmount(10_000), "a budget under the network cost sends nothing")
    }

    private fun terms(vararg bands: FeeRuleBand) = FeeRuleTerms(999, 10_000_000_000, null, 2_500, 2_000, bands.toList())

    private fun problems(terms: FeeRuleTerms): Map<String, String> {
        val checked = RuleChecks.fee(terms)
        assertIs<Resource.Error>(checked)
        assertEquals("wallet.rule_invalid", checked.error.code)
        return checked.error.details!!
            .fields
            .associate { it.field to it.problem }
    }

    @Test
    fun `bands are stored with the first band's rate, and one open band is stored as the flat rate`() {
        val stored =
            (
                RuleChecks.fee(
                    terms(FeeRuleBand(0, usdc, 200), FeeRuleBand(usdc, null, 100)),
                ) as Resource.Success
            ).data
        assertEquals(200, stored.rateBps)
        assertEquals(2, stored.bands.size)

        val flat = (RuleChecks.fee(terms(FeeRuleBand(0, null, 75))) as Resource.Success).data
        assertEquals(75, flat.rateBps)
        assertEquals(emptyList(), flat.bands)
    }

    @Test
    fun `gaps, overlaps, a missing start, an end on the last band and bad rates are refused by field`() {
        assertEquals(
            "the first band starts at 0",
            problems(terms(FeeRuleBand(1, null, 100)))["bands[0].fromUsdMicros"],
        )
        assertEquals(
            "must be where the band before ends",
            problems(terms(FeeRuleBand(0, usdc, 200), FeeRuleBand(2 * usdc, null, 100)))["bands[1].fromUsdMicros"],
            "a gap",
        )
        assertEquals(
            "must be where the band before ends",
            problems(terms(FeeRuleBand(0, 2 * usdc, 200), FeeRuleBand(usdc, null, 100)))["bands[1].fromUsdMicros"],
            "an overlap",
        )
        assertEquals(
            "the last band has no end",
            problems(terms(FeeRuleBand(0, usdc, 200)))["bands[0].toUsdMicros"],
        )
        assertEquals(
            "only the last band has no end",
            problems(terms(FeeRuleBand(0, null, 200), FeeRuleBand(usdc, null, 100)))["bands[0].toUsdMicros"],
        )
        assertEquals(
            "must be above from",
            problems(terms(FeeRuleBand(0, 0, 200), FeeRuleBand(0, null, 100)))["bands[0].toUsdMicros"],
        )
        assertEquals(
            "must be 0 to 10000",
            problems(terms(FeeRuleBand(0, usdc, 10_001), FeeRuleBand(usdc, null, 100)))["bands[0].rateBps"],
        )
        val many = (0 until 21).map { FeeRuleBand(it * usdc, if (it == 20) null else (it + 1) * usdc, 100) }
        assertEquals("at most 20 bands", problems(terms(*many.toTypedArray()))["bands"])
    }
}
