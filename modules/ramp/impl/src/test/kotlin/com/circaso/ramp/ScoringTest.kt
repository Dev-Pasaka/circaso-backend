package com.circaso.ramp

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.RampOrderId
import com.circaso.ramp.domain.models.Attribution
import com.circaso.ramp.domain.models.CandidateFacts
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.EngineMode
import com.circaso.ramp.domain.models.EngineSettings
import com.circaso.ramp.domain.models.Exclusion
import com.circaso.ramp.domain.models.FinishedOrder
import com.circaso.ramp.domain.models.MerchantStats
import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.models.RoutingOrder
import com.circaso.ramp.domain.support.Router
import com.circaso.ramp.domain.support.Scoring
import com.circaso.ramp.domain.support.Stats
import com.circaso.ramp.domain.support.WeightedDraw
import com.circaso.shared.Network
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The engine's arithmetic (decision 2026-11-23): each formula as a table, the hard filter's reasons, the weighted
 * draw, and the properties the engine must never lose: nobody eligible is starved, and nobody ineligible is picked.
 */
class ScoringTest {
    private val now: Instant = Instant.parse("2026-11-23T12:00:00Z")
    private val settings = EngineSettings.defaults(Network.TESTNET)

    /** 5 000 shillings for 38 USDC: above the small-order line, so the size fit is in play. */
    private val order = RoutingOrder(Direction.ON_RAMP, fiatMinor = 500_000, needMinor = 38_000_000)

    private fun stats(
        completed: Int = 0,
        failed: Int = 0,
        expiries: Int = 0,
        notCounted: Int = 0,
        median: Long? = null,
    ) = MerchantStats(completed + failed + expiries + notCounted, completed, failed, expiries, 0, median, median)

    private fun facts(
        floatMinor: Long? = 1_000_000_000,
        lastAssignedAt: Instant? = now,
        trades: Int = 100,
        stats: MerchantStats? = stats(),
        limitMinor: Long = 3_000_000,
    ) = CandidateFacts(
        merchantId = MerchantId.new(),
        approved = true,
        kycCleared = true,
        online = true,
        hasKeys = true,
        paymentsIssue = false,
        offersDirection = true,
        limitMinor = limitMinor,
        floatMinor = floatMinor,
        lastAssignedAt = lastAssignedAt,
        completedTrades = trades,
        stats = stats,
    )

    private fun assertClose(
        expected: Double,
        actual: Double,
        what: String = "",
    ) = assertTrue(abs(expected - actual) < 1e-9, "$what expected $expected but was $actual")

    // The defaults the owner asked for

    @Test
    fun `the seed is the agreed one - active on the testnet, shadow on mainnet`() {
        assertEquals(EngineMode.ACTIVE, settings.mode)
        assertEquals(EngineMode.SHADOW, EngineSettings.defaults(Network.MAINNET).mode)
        assertEquals(
            Triple(0.5, 0.3, 0.2),
            Triple(settings.weightCompletion, settings.weightSpeed, settings.weightFailure),
        )
        assertEquals(10.0 to 0.9, settings.priorStrength to settings.completionPrior)
        assertEquals(0.1 to 1.0, settings.qualityFloor to settings.alpha)
        assertEquals(1.0 to 120, settings.hungerMax to settings.hungerWindowMinutes)
        assertEquals(0.5 to 20, settings.newcomerMax to settings.newcomerTrades)
        assertEquals(200_000L, settings.smallOrderMinor, "KES 2 000")
        assertEquals(3.0, settings.floatCover)
        assertEquals(0.25 to 0.5, settings.sharePenalty to settings.shareThreshold)
        assertEquals(900 to 300, settings.slaSeconds to settings.targetSeconds)
        assertEquals(50 to 30, settings.statsOrders to settings.statsDays)
        assertEquals(3, settings.maxAttempts)
        assertEquals(3_000_000L to 15_000_000L, settings.individualLimitMinor to settings.businessLimitMinor)
        assertNull(settings.problem(), "and it is valid")
    }

    // Formulas, as tables

    @Test
    fun `completion is smoothed toward the prior - C = (completed + k p0) over (counted + k)`() {
        // completed, failed, expected C with k = 10, p0 = 0.9
        listOf(
            Triple(0, 0, 0.9),
            Triple(1, 0, 10.0 / 11),
            Triple(0, 1, 9.0 / 11),
            Triple(40, 10, 49.0 / 60),
            Triple(50, 0, 59.0 / 60),
        ).forEach { (completed, failed, expected) ->
            assertClose(expected, Scoring.completion(stats(completed, failed), settings), "C($completed, $failed)")
        }
        val raw = settings.copy(priorStrength = 0.0)
        assertClose(0.8, Scoring.completion(stats(40, 10), raw), "with no prior it is the plain rate")
        assertClose(0.9, Scoring.completion(stats(), raw), "and the prior when there is nothing at all")
    }

    @Test
    fun `failure is smoothed the same way with its own prior, and buyer-caused orders are in neither rate`() {
        // completed, failed, expected F with k = 10, f0 = 0.1
        listOf(
            Triple(0, 0, 0.1),
            Triple(0, 1, 2.0 / 11),
            Triple(40, 10, 11.0 / 60),
            Triple(50, 0, 1.0 / 60),
        ).forEach { (completed, failed, expected) ->
            assertClose(expected, Scoring.failure(stats(completed, failed), settings), "F($completed, $failed)")
        }
        val clean = stats(completed = 40)
        val ignored = stats(completed = 40, notCounted = 60)
        assertClose(Scoring.completion(clean, settings), Scoring.completion(ignored, settings), "C")
        assertClose(Scoring.failure(clean, settings), Scoring.failure(ignored, settings), "F")
        assertClose(
            Scoring.failure(stats(completed = 40, failed = 2), settings),
            Scoring.failure(stats(completed = 40, expiries = 2), settings),
            "a merchant's expiry counts as a failure does",
        )
    }

    @Test
    fun `speed is full within the target, falls in a straight line, and is zero at twice the sla`() {
        // median seconds, expected S with target 300 and SLA 900 (zero at 1 800)
        listOf(
            null to 0.9,
            0L to 1.0,
            300L to 1.0,
            1_050L to 0.5,
            1_500L to 0.2,
            1_800L to 0.0,
            9_000L to 0.0,
        ).forEach { (median, expected) ->
            assertClose(expected, Scoring.speed(stats(completed = 5, median = median), settings), "S($median)")
        }
    }

    @Test
    fun `quality is the weighted sum - Q = wc C + ws S + wf (1 - F)`() {
        assertClose(0.5 * 0.8 + 0.3 * 0.5 + 0.2 * (1 - 0.25), Scoring.quality(0.8, 0.5, 0.25, settings))
        assertClose(1.0, Scoring.quality(1.0, 1.0, 0.0, settings), "the best there is")
        assertClose(0.0, Scoring.quality(0.0, 0.0, 1.0, settings), "the worst")
        val unknown = Scoring.score(facts(), order, settings, now)
        assertClose(0.5 * 0.9 + 0.3 * 0.9 + 0.2 * 0.9, unknown.quality, "nothing known: the priors")
    }

    @Test
    fun `hunger grows with the wait since the last order and stops at the window`() {
        // minutes since the last order, expected hunger with hMax 1 and a 120 minute window
        listOf(0L to 0.0, 30L to 0.25, 60L to 0.5, 120L to 1.0, 600L to 1.0).forEach { (minutes, expected) ->
            val last = now.minus(Duration.ofMinutes(minutes))
            assertClose(expected, Scoring.hunger(last, now, settings), "hunger after $minutes min")
        }
        assertClose(1.0, Scoring.hunger(null, now, settings), "never assigned: the whole boost")
        assertClose(0.0, Scoring.hunger(now.plusSeconds(60), now, settings), "a clock behind is no wait")
        assertClose(2.0, Scoring.hunger(null, now, settings.copy(hungerMax = 2.0)))
    }

    @Test
    fun `the newcomer boost fades with completed trades and is gone at the line`() {
        // completed trades, expected boost with nMax 0.5 over 20 trades
        listOf(0 to 0.5, 5 to 0.375, 10 to 0.25, 19 to 0.025, 20 to 0.0, 500 to 0.0).forEach { (trades, expected) ->
            assertClose(expected, Scoring.newcomer(trades, settings), "newcomer at $trades trades")
        }
    }

    @Test
    fun `size fit - small orders fit everyone, large ones need cover, and taking most of a float is penalised`() {
        val small = RoutingOrder(Direction.ON_RAMP, fiatMinor = 200_000, needMinor = 15_000_000)
        assertClose(1.0, Scoring.sizeFit(small, 15_000_000, settings), "at the small-order line: no size question")

        // free float as a multiple of the order, expected fit with cover 3, a 0.25 penalty above half the float
        listOf(
            10.0 to 1.0,
            3.0 to 1.0,
            2.5 to 2.5 / 3,
            2.0 to 2.0 / 3,
            1.5 to (1.5 / 3) * 0.25,
            1.0 to (1.0 / 3) * 0.25,
        ).forEach { (multiple, expected) ->
            val float = (order.needMinor * multiple).toLong()
            assertClose(expected, Scoring.sizeFit(order, float, settings), "fit with ${multiple}x the order")
        }
    }

    @Test
    fun `the weight is max(Q, floor) to the alpha, times hunger, newcomer and size fit`() {
        val waited = now.minus(Duration.ofMinutes(60))
        val one = facts(lastAssignedAt = waited, trades = 10, stats = stats(40, 10, median = 1_050))
        val score = Scoring.score(one, order, settings, now)

        val quality = 0.5 * (49.0 / 60) + 0.3 * 0.5 + 0.2 * (1 - 11.0 / 60)
        assertClose(quality, score.quality)
        assertClose(quality * (1 + 0.5) * (1 + 0.25) * 1.0, score.weight)

        val poor = facts(stats = stats(0, 50, median = 9_000))
        val floored = Scoring.score(poor, order, settings.copy(priorStrength = 0.0), now)
        assertClose(0.0, floored.quality)
        assertClose(0.1, floored.weight, "the floor: a bad record still leaves a chance")

        val squared = Scoring.score(one, order, settings.copy(alpha = 2.0), now)
        assertClose(quality * quality * 1.5 * 1.25, squared.weight, "alpha sharpens quality")
        val flat = Scoring.score(one, order, settings.copy(alpha = 0.0), now)
        assertClose(1.5 * 1.25, flat.weight, "alpha zero ignores it")
    }

    // Eligibility

    @Test
    fun `every way of being left out has its own reason, the first that applies`() {
        val fine = facts()
        assertNull(Scoring.exclusion(fine, order))

        listOf(
            fine.copy(approved = false) to Exclusion.NOT_APPROVED,
            fine.copy(kycCleared = false) to Exclusion.KYC_REQUIRED,
            fine.copy(online = false) to Exclusion.OFFLINE,
            fine.copy(hasKeys = false) to Exclusion.NO_KEYS,
            fine.copy(paymentsIssue = true) to Exclusion.PAYMENTS_ISSUE,
            fine.copy(offersDirection = false) to Exclusion.DIRECTION_OFF,
            fine.copy(limitMinor = order.fiatMinor - 1) to Exclusion.OVER_LIMIT,
            fine.copy(floatMinor = null) to Exclusion.FLOAT_UNKNOWN,
            fine.copy(floatMinor = order.needMinor - 1) to Exclusion.FLOAT_SHORT,
        ).forEach { (candidate, reason) -> assertEquals(reason, Scoring.exclusion(candidate, order)) }

        assertNull(Scoring.exclusion(fine.copy(limitMinor = order.fiatMinor), order), "at the limit is within it")
        assertNull(Scoring.exclusion(fine.copy(floatMinor = order.needMinor), order), "float that just covers it")
        val everything = fine.copy(approved = false, online = false, floatMinor = null)
        assertEquals(Exclusion.NOT_APPROVED, Scoring.exclusion(everything, order), "the first in the order")
    }

    // The draw

    @Test
    fun `the weighted draw picks in proportion to the weights`() {
        val random = Random(42)
        val weighted = listOf("a" to 1.0, "b" to 2.0, "c" to 7.0)
        val draws = 20_000

        val first = (1..draws).map { WeightedDraw.rank(weighted, random).first() }.groupingBy { it }.eachCount()

        assertEquals(setOf("a", "b", "c"), first.keys)
        assertTrue(abs(first.getValue("a") / draws.toDouble() - 0.1) < 0.01, "a: ${first["a"]}")
        assertTrue(abs(first.getValue("b") / draws.toDouble() - 0.2) < 0.01, "b: ${first["b"]}")
        assertTrue(abs(first.getValue("c") / draws.toDouble() - 0.7) < 0.01, "c: ${first["c"]}")
    }

    @Test
    fun `a draw is a whole failover order, the same for the same seed`() {
        val weighted = (1..8).map { "m$it" to it.toDouble() }

        val once = WeightedDraw.rank(weighted, Random(7))
        val again = WeightedDraw.rank(weighted, Random(7))

        assertEquals(once, again, "the source decides, nothing else")
        assertEquals(weighted.map { it.first }.toSet(), once.toSet(), "everybody, once")
        assertEquals(8, once.size)
        assertTrue(WeightedDraw.rank(emptyList<Pair<String, Double>>(), Random(7)).isEmpty())
        val others = (1..20).map { WeightedDraw.rank(weighted, Random(it)) }.toSet()
        assertTrue(others.size > 1, "another seed, another order")
    }

    @Test
    fun `no starvation - every eligible merchant has a chance, whatever the valid settings and their record`() {
        val random = Random(2026)
        repeat(300) {
            val drawn = randomSettings(random)
            assertNull(drawn.problem(), "the generator makes valid settings: $drawn")
            val pool = (1..6).map { randomFacts(random, eligible = true) }
            pool.forEach { one ->
                val score = Scoring.score(one, order, drawn, now)
                assertTrue(
                    score.weight > 0.0 && score.weight.isFinite(),
                    "weight ${score.weight} under $drawn for $one",
                )
            }
        }

        // and the least-favoured of a pool really is picked, given enough draws
        val worst =
            facts(
                floatMinor = order.needMinor,
                lastAssignedAt = now,
                trades = 1_000,
                stats = stats(0, 50, median = 9_000),
            )
        val best = facts(lastAssignedAt = null, trades = 0, stats = stats(50, 0, median = 10))
        val picks =
            (1..5_000).count {
                Router.plan(order, listOf(best, worst), settings, now, random).pick == worst.merchantId
            }
        assertTrue(picks > 0, "the worst merchant was never picked in 5 000 draws")
        assertTrue(picks < 1_000, "but far less often than the best: $picks")
    }

    @Test
    fun `nobody ineligible is ever picked or ranked, in any mode`() {
        val random = Random(99)
        repeat(500) {
            val drawn = randomSettings(random).copy(mode = EngineMode.entries.random(random))
            val pool = (1..7).map { randomFacts(random, eligible = random.nextBoolean()) }

            val plan = Router.plan(order, pool, drawn, now, random)

            val eligible = pool.filter { Scoring.exclusion(it, order) == null }.map { it.merchantId }.toSet()
            assertEquals(eligible, plan.ranked.toSet(), "the ranking is exactly the eligible")
            assertEquals(eligible.size, plan.ranked.size, "each once")
            plan.engineRanked?.let { assertEquals(eligible, it.toSet()) }
            assertTrue(plan.pick == null || plan.pick in eligible)
            plan.candidates.filter { !it.isEligible }.forEach { out ->
                assertNotNull(out.exclusion, "everyone left out has a reason")
                assertNull(out.score, "and no score")
            }
        }
    }

    // Modes

    @Test
    fun `round robin takes turns, shadow scores but round robin still picks, active follows the draw`() {
        val rested = facts(lastAssignedAt = now.minus(Duration.ofHours(5)), stats = stats(0, 50, median = 9_000))
        val busy = facts(lastAssignedAt = now.minusSeconds(60), stats = stats(50, 0, median = 10))
        val fresh = facts(lastAssignedAt = null)
        val pool = listOf(busy, rested, fresh)
        val turns = listOf(fresh.merchantId, rested.merchantId, busy.merchantId)

        val roundRobin = Router.plan(order, pool, settings.copy(mode = EngineMode.ROUND_ROBIN), now, Random(1))
        assertEquals(turns, roundRobin.ranked, "never assigned first, then the longest wait")
        assertNull(roundRobin.engineRanked)
        assertTrue(roundRobin.candidates.all { it.score == null }, "nothing is scored")

        val shadow = Router.plan(order, pool, settings.copy(mode = EngineMode.SHADOW), now, Random(1))
        assertEquals(turns, shadow.ranked, "round robin still picks")
        assertEquals(
            pool.map { it.merchantId }.toSet(),
            shadow.engineRanked!!.toSet(),
            "the engine's draw is kept beside it",
        )
        assertTrue(shadow.candidates.all { it.score != null })

        val active = Router.plan(order, pool, settings.copy(mode = EngineMode.ACTIVE), now, Random(1))
        assertEquals(active.engineRanked, active.ranked, "the draw is the order")
        assertEquals(shadow.engineRanked, active.ranked, "the very draw shadow only logged, for the same seed")
    }

    // Statistics

    @Test
    fun `only what is down to the merchant counts against them - a buyer who ignores or cancels never does`() {
        listOf(
            Triple(OrderState.COMPLETED, null, Attribution.COMPLETED),
            Triple(OrderState.FAILED, "prompt_refused", Attribution.MERCHANT_FAILURE),
            Triple(OrderState.FAILED, "provider_balance", Attribution.MERCHANT_FAILURE),
            Triple(OrderState.FAILED, "merchant_offline", Attribution.MERCHANT_FAILURE),
            Triple(OrderState.FAILED, "payout_failed", Attribution.MERCHANT_FAILURE),
            Triple(OrderState.EXPIRED, "payout_missed", Attribution.MERCHANT_EXPIRY),
            // the buyer ignored, declined or mistyped the M-Pesa prompt: the provider reports the payment failed
            Triple(OrderState.FAILED, "payment_failed", Attribution.NOT_COUNTED),
            Triple(OrderState.CANCELLED, "cancelled", Attribution.NOT_COUNTED),
            // the provider, or Circaso itself
            Triple(OrderState.FAILED, "provider_unreachable", Attribution.NOT_COUNTED),
            Triple(OrderState.FAILED, "prompt_failed", Attribution.NOT_COUNTED),
            Triple(OrderState.FAILED, "prompt_unconfirmed", Attribution.NOT_COUNTED),
            Triple(OrderState.FAILED, "provider_unavailable", Attribution.NOT_COUNTED),
            Triple(OrderState.EXPIRED, "never_prompted", Attribution.NOT_COUNTED),
            // a reason nobody classified costs nobody anything
            Triple(OrderState.FAILED, "something_new", Attribution.NOT_COUNTED),
            Triple(OrderState.FAILED, null, Attribution.NOT_COUNTED),
            Triple(OrderState.EXPIRED, "prompt_refused", Attribution.NOT_COUNTED),
        ).forEach { (state, reason, expected) ->
            assertEquals(expected, Stats.attribution(state, reason), "$state $reason")
        }
    }

    @Test
    fun `statistics add up the window - counts, the median and the ninetieth percentile of handling time`() {
        fun finished(
            state: OrderState,
            reason: String? = null,
            handling: Long? = null,
        ) = FinishedOrder(RampOrderId.new(), state, reason, now, handling)
        val window =
            listOf(
                10L,
                20L,
                30L,
                40L,
                50L,
                60L,
                70L,
                80L,
                90L,
                1_000L,
            ).map { finished(OrderState.COMPLETED, null, it) } +
                finished(OrderState.COMPLETED) +
                finished(OrderState.FAILED, "prompt_refused") +
                finished(OrderState.FAILED, "provider_balance")

        // beside them, four orders that aren't the merchant's record: two lapsed prompts, a cancel, a lost prompt
        val stats = Stats.of(window, notCounted = 4)

        assertEquals(17, stats.finished)
        assertEquals(11, stats.completed)
        assertEquals(2, stats.merchantFailures)
        assertEquals(0, stats.merchantExpiries)
        assertEquals(0, stats.reversals, "0 until reversal reports are built")
        assertEquals(13, stats.counted)
        assertEquals(4, stats.notCounted)
        assertEquals(50L, stats.medianHandlingSeconds, "of the ten that were timed")
        assertEquals(90L, stats.p90HandlingSeconds, "one slow order doesn't move the ninetieth percentile's rank")

        val stray = Stats.of(window + finished(OrderState.FAILED, "payment_failed"))
        assertEquals(13 to 1, stray.counted to stray.notCounted, "an uncounted order in the window stays uncounted")
        assertTrue(Stats.isCounted(OrderState.COMPLETED, null) && Stats.isCounted(OrderState.FAILED, "prompt_refused"))
        assertTrue(
            !Stats.isCounted(OrderState.FAILED, "payment_failed") && !Stats.isCounted(OrderState.CANCELLED, null),
        )

        assertEquals(MerchantStats.NONE, Stats.of(emptyList()))
        assertNull(Stats.percentile(emptyList(), 0.5))
        assertEquals(7L, Stats.percentile(listOf(7L), 0.9))
        assertEquals(2L, Stats.percentile(listOf(1L, 2L, 3L), 0.5))
    }

    // Validation

    @Test
    fun `settings out of range are refused, naming the field`() {
        listOf(
            settings.copy(weightCompletion = 1.5) to "weightCompletion",
            settings.copy(weightSpeed = -0.1) to "weightSpeed",
            settings.copy(weightFailure = Double.NaN) to "weightFailure",
            settings.copy(weightCompletion = 0.6) to "weightCompletion",
            settings.copy(priorStrength = -1.0) to "priorStrength",
            settings.copy(completionPrior = 1.1) to "completionPrior",
            settings.copy(failurePrior = -0.1) to "failurePrior",
            settings.copy(speedPrior = 2.0) to "speedPrior",
            settings.copy(qualityFloor = 0.0) to "qualityFloor",
            settings.copy(alpha = 6.0) to "alpha",
            settings.copy(hungerMax = -1.0) to "hungerMax",
            settings.copy(hungerWindowMinutes = 0) to "hungerWindowMinutes",
            settings.copy(newcomerMax = 11.0) to "newcomerMax",
            settings.copy(newcomerTrades = 0) to "newcomerTrades",
            settings.copy(smallOrderMinor = -1) to "smallOrderMinor",
            settings.copy(floatCover = 0.5) to "floatCover",
            settings.copy(shareThreshold = 0.0) to "shareThreshold",
            settings.copy(sharePenalty = 0.0) to "sharePenalty",
            settings.copy(individualLimitMinor = 0) to "individualLimitMinor",
            settings.copy(businessLimitMinor = -5) to "businessLimitMinor",
            settings.copy(targetSeconds = 0) to "targetSeconds",
            settings.copy(slaSeconds = 299) to "slaSeconds",
            settings.copy(statsOrders = 0) to "statsOrders",
            settings.copy(statsDays = 366) to "statsDays",
            settings.copy(maxAttempts = 0) to "maxAttempts",
            settings.copy(maxAttempts = 11) to "maxAttempts",
            settings.copy(logRetentionDays = 0) to "logRetentionDays",
        ).forEach { (invalid, field) -> assertEquals(field, invalid.problem()?.first, "$invalid") }

        val reweighted = settings.copy(weightCompletion = 0.2, weightSpeed = 0.2, weightFailure = 0.6)
        assertNull(reweighted.problem(), "other weights that add up to 1 are fine")
        assertNull(settings.copy(slaSeconds = 300).problem(), "an SLA equal to the target")
    }

    private fun randomSettings(random: Random): EngineSettings {
        val wc = random.nextDouble()
        val ws = random.nextDouble() * (1 - wc)
        val target = random.nextInt(1, 2_000)
        return settings.copy(
            weightCompletion = wc,
            weightSpeed = ws,
            weightFailure = 1 - wc - ws,
            priorStrength = random.nextDouble(0.0, 100.0),
            completionPrior = random.nextDouble(),
            failurePrior = random.nextDouble(),
            speedPrior = random.nextDouble(),
            qualityFloor = random.nextDouble(0.001, 1.0),
            alpha = random.nextDouble(0.0, 5.0),
            hungerMax = random.nextDouble(0.0, 10.0),
            hungerWindowMinutes = random.nextInt(1, 10_080),
            newcomerMax = random.nextDouble(0.0, 10.0),
            newcomerTrades = random.nextInt(1, 10_000),
            smallOrderMinor = random.nextLong(0, 1_000_000),
            floatCover = random.nextDouble(1.0, 100.0),
            shareThreshold = random.nextDouble(0.001, 1.0),
            sharePenalty = random.nextDouble(0.001, 1.0),
            targetSeconds = target,
            slaSeconds = target + random.nextInt(0, 5_000),
        )
    }

    private fun randomFacts(random: Random, eligible: Boolean): CandidateFacts {
        val completed = random.nextInt(0, 50)
        val failed = random.nextInt(0, 50)
        val fine =
            facts(
                floatMinor = order.needMinor + random.nextLong(0, 1_000_000_000),
                lastAssignedAt = if (random.nextBoolean()) null else now.minusSeconds(random.nextLong(0, 100_000)),
                trades = random.nextInt(0, 500),
                stats = stats(completed, failed, median = if (completed == 0) null else random.nextLong(0, 10_000)),
            )
        if (eligible) return fine
        return when (random.nextInt(9)) {
            0 -> fine.copy(approved = false)
            1 -> fine.copy(kycCleared = false)
            2 -> fine.copy(online = false)
            3 -> fine.copy(hasKeys = false)
            4 -> fine.copy(paymentsIssue = true)
            5 -> fine.copy(offersDirection = false)
            6 -> fine.copy(limitMinor = order.fiatMinor - 1)
            7 -> fine.copy(floatMinor = null)
            else -> fine.copy(floatMinor = order.needMinor - 1)
        }
    }
}
