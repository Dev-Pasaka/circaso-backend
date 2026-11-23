package com.circaso.ramp.domain.support

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.models.Attribution
import com.circaso.ramp.domain.models.CandidateFacts
import com.circaso.ramp.domain.models.EngineMode
import com.circaso.ramp.domain.models.EngineSettings
import com.circaso.ramp.domain.models.Exclusion
import com.circaso.ramp.domain.models.FinishedOrder
import com.circaso.ramp.domain.models.MerchantStats
import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.models.RoutedCandidate
import com.circaso.ramp.domain.models.RoutingOrder
import com.circaso.ramp.domain.models.RoutingPlan
import com.circaso.ramp.domain.models.Score
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * The engine's arithmetic (decision 2026-11-23), with nothing in it but numbers: no clock, no database, no provider.
 * Everything here is a pure function of its arguments, so the formulas are tested as tables.
 */
object Scoring {
    /**
     * Why [facts] can't take [order], or null when they can. A hard filter: the first reason that applies, in the
     * order of [Exclusion]. The float is looked at last, because reading it costs a call to the custodian.
     */
    fun exclusion(facts: CandidateFacts, order: RoutingOrder): Exclusion? =
        beforeFloat(facts, order) ?: floatExclusion(facts.floatMinor, order)

    /** Everything [exclusion] checks that needs no float. */
    fun beforeFloat(facts: CandidateFacts, order: RoutingOrder): Exclusion? =
        when {
            !facts.approved -> Exclusion.NOT_APPROVED
            !facts.kycCleared -> Exclusion.KYC_REQUIRED
            !facts.online -> Exclusion.OFFLINE
            !facts.hasKeys -> Exclusion.NO_KEYS
            facts.paymentsIssue -> Exclusion.PAYMENTS_ISSUE
            !facts.offersDirection -> Exclusion.DIRECTION_OFF
            order.fiatMinor > facts.limitMinor -> Exclusion.OVER_LIMIT
            else -> null
        }

    private fun floatExclusion(floatMinor: Long?, order: RoutingOrder): Exclusion? =
        when {
            floatMinor == null -> Exclusion.FLOAT_UNKNOWN
            floatMinor < order.needMinor -> Exclusion.FLOAT_SHORT
            else -> null
        }

    /** `C = (completed + k·p0) / (counted + k)`: the completion rate, pulled toward the prior while data is thin. */
    fun completion(stats: MerchantStats, settings: EngineSettings): Double =
        smoothed(stats.completed, stats.counted, settings.completionPrior, settings.priorStrength)

    /** `F = (failed + k·f0) / (counted + k)`: the failure rate, smoothed the same way with its own prior. */
    fun failure(stats: MerchantStats, settings: EngineSettings): Double =
        smoothed(stats.failed, stats.counted, settings.failurePrior, settings.priorStrength)

    private fun smoothed(
        hits: Int,
        outOf: Int,
        prior: Double,
        strength: Double,
    ): Double {
        val weight = outOf + strength
        return if (weight <= 0.0) prior else (hits + strength * prior) / weight
    }

    /**
     * `S`: 1 while the median handling time is within the target, falling in a straight line to 0 at twice the SLA.
     * A merchant with nothing timed yet gets the prior.
     */
    fun speed(stats: MerchantStats, settings: EngineSettings): Double {
        val median = stats.medianHandlingSeconds ?: return settings.speedPrior
        val target = settings.targetSeconds.toDouble()
        val zeroAt = 2.0 * settings.slaSeconds
        return when {
            median <= target -> 1.0
            median >= zeroAt -> 0.0
            else -> 1.0 - (median - target) / (zeroAt - target)
        }
    }

    /** `Q = wc·C + ws·S + wf·(1 − F)`. */
    fun quality(
        completion: Double,
        speed: Double,
        failure: Double,
        settings: EngineSettings,
    ): Double =
        settings.weightCompletion * completion + settings.weightSpeed * speed +
            settings.weightFailure * (1.0 - failure)

    /**
     * `hMax × min(1, minutes since the last order / window)`: the longer a merchant has waited, the more they are
     * owed a turn. Someone never assigned gets the whole boost.
     */
    fun hunger(
        lastAssignedAt: Instant?,
        now: Instant,
        settings: EngineSettings,
    ): Double {
        if (lastAssignedAt == null) return settings.hungerMax
        val waitedMinutes = max(0.0, Duration.between(lastAssignedAt, now).seconds / SECONDS_PER_MINUTE)
        return settings.hungerMax * min(1.0, waitedMinutes / settings.hungerWindowMinutes)
    }

    /** `nMax × max(0, 1 − trades / newcomerTrades)`: a new merchant's help, gone once they have traded enough. */
    fun newcomer(completedTrades: Int, settings: EngineSettings): Double =
        settings.newcomerMax * max(0.0, 1.0 - completedTrades.toDouble() / settings.newcomerTrades)

    /**
     * How well the order fits the float: 1 for a small order; otherwise `min(1, float / (order × cover))`, and
     * multiplied by the share penalty when this one order would take more than the threshold of the float.
     */
    fun sizeFit(
        order: RoutingOrder,
        floatMinor: Long,
        settings: EngineSettings,
    ): Double {
        if (order.fiatMinor <= settings.smallOrderMinor) return 1.0
        if (order.needMinor <= 0) return 1.0
        val covered = min(1.0, floatMinor / (order.needMinor * settings.floatCover))
        val takesTooMuch = order.needMinor > settings.shareThreshold * floatMinor
        return if (takesTooMuch) covered * settings.sharePenalty else covered
    }

    /** `W = max(Q, floor)^alpha × (1 + hunger) × (1 + newcomer) × sizeFit`, with every factor kept. */
    fun score(
        facts: CandidateFacts,
        order: RoutingOrder,
        settings: EngineSettings,
        now: Instant,
    ): Score {
        val stats = facts.stats ?: MerchantStats.NONE
        val completion = completion(stats, settings)
        val speed = speed(stats, settings)
        val failure = failure(stats, settings)
        val quality = quality(completion, speed, failure, settings)
        val hunger = hunger(facts.lastAssignedAt, now, settings)
        val newcomer = newcomer(facts.completedTrades, settings)
        val sizeFit = sizeFit(order, facts.floatMinor ?: 0, settings)
        val weight = max(quality, settings.qualityFloor).pow(settings.alpha) * (1 + hunger) * (1 + newcomer) * sizeFit
        return Score(completion, speed, failure, quality, hunger, newcomer, sizeFit, weight)
    }

    private const val SECONDS_PER_MINUTE = 60.0
}

/**
 * A weighted random order: each place goes to one of those left, with a chance in proportion to its weight. The first
 * is the pick and the rest are the failover order, so a heavier merchant is tried sooner but nobody with a weight
 * above zero is ever impossible.
 */
object WeightedDraw {
    fun <T> rank(weighted: List<Pair<T, Double>>, random: Random): List<T> {
        val left = weighted.map { (item, weight) -> item to usable(weight) }.toMutableList()
        val ranked = ArrayList<T>(left.size)
        while (left.isNotEmpty()) {
            val drawn = draw(left, random)
            ranked += left.removeAt(drawn).first
        }
        return ranked
    }

    private fun <T> draw(left: List<Pair<T, Double>>, random: Random): Int {
        var remaining = random.nextDouble() * left.sumOf { it.second }
        left.forEachIndexed { index, (_, weight) ->
            remaining -= weight
            if (remaining < 0) return index
        }
        return left.lastIndex
    }

    /** A weight that isn't a positive number still gets the smallest chance, never none and never the whole draw. */
    private fun usable(weight: Double): Double = if (weight.isFinite() && weight > MINIMUM) weight else MINIMUM

    private const val MINIMUM = 1e-12
}

/** The engine's decision for one order, from facts already gathered: who is out and why, who scored what, the order. */
object Router {
    fun plan(
        order: RoutingOrder,
        facts: List<CandidateFacts>,
        settings: EngineSettings,
        now: Instant,
        random: Random,
    ): RoutingPlan {
        val scores = settings.mode != EngineMode.ROUND_ROBIN
        val candidates =
            facts.map { candidate ->
                val exclusion = Scoring.exclusion(candidate, order)
                val score = if (exclusion == null && scores) Scoring.score(candidate, order, settings, now) else null
                RoutedCandidate(candidate, exclusion, score)
            }
        val eligible = candidates.filter { it.isEligible }
        val roundRobin = roundRobin(eligible)
        val engineRanked =
            if (scores) {
                WeightedDraw.rank(eligible.map { it.facts.merchantId to (it.score?.weight ?: 0.0) }, random)
            } else {
                null
            }
        val ranked = if (settings.mode == EngineMode.ACTIVE) engineRanked.orEmpty() else roundRobin
        return RoutingPlan(settings.mode, candidates, ranked, engineRanked)
    }

    /** The least recently assigned first, so equal merchants take turns; never assigned goes before everyone. */
    fun roundRobin(eligible: List<RoutedCandidate>): List<MerchantId> =
        eligible.sortedWith(compareBy(nullsFirst()) { it.facts.lastAssignedAt }).map { it.facts.merchantId }
}

/**
 * A merchant's statistics from their finished orders (decision 2026-11-23).
 *
 * **What counts against a merchant.** A `FAILED` order whose reason is one of [MERCHANT_FAILURES]: their provider
 * refused the prompt (`prompt_refused`), their prepaid balance there was empty (`provider_balance`), they were
 * offline when the prompt had to go out (`merchant_offline`), or (selling) their payout failed (`payout_failed`). An
 * `EXPIRED` order whose reason is one of [MERCHANT_EXPIRIES] (a payout the merchant never made; nothing on the
 * on-ramp expires because of a merchant). **Everything else is not counted at all**, neither for nor against: a
 * buyer who ignored or cancelled the M-Pesa prompt (`payment_failed`), a cancelled order, the provider out of reach,
 * a prompt that never went out. A reason nobody has classified is not counted either: it must be added here on
 * purpose before it can cost a merchant anything.
 *
 * **The window is the merchant's last counted orders**, not their last orders of any kind: a hundred buyers letting
 * a prompt lapse neither lower a rate nor push the merchant's real record out of sight.
 */
object Stats {
    val MERCHANT_FAILURES = setOf("prompt_refused", "provider_balance", "merchant_offline", "payout_failed")
    val MERCHANT_EXPIRIES = setOf("payout_missed")

    /**
     * The states an order is finished in. `REVIEW` is not one: a person still decides it. A sale the merchant couldn't
     * pay ends `REFUNDED` with the reason that counts (`payout_failed`, `payout_missed`); one that was never funded
     * ends `EXPIRED` (`never_funded`) or `CANCELLED`, which is the user's doing and never counts.
     */
    val FINISHED =
        setOf(OrderState.COMPLETED, OrderState.FAILED, OrderState.EXPIRED, OrderState.CANCELLED, OrderState.REFUNDED)

    /** What `order_events_finished_idx` indexes. */
    val FINISHED_NAMES: List<String> = FINISHED.map { it.name }

    /** The step handling time is counted from, by direction: the verified payment, or (selling) the funds arriving. */
    const val HANDLING_FROM_ON_RAMP = "PAID"
    const val HANDLING_FROM_OFF_RAMP = "FUNDED"

    fun attribution(state: OrderState, reason: String?): Attribution =
        when {
            state == OrderState.COMPLETED -> Attribution.COMPLETED

            state == OrderState.FAILED && reason in MERCHANT_FAILURES -> Attribution.MERCHANT_FAILURE

            state == OrderState.EXPIRED && reason in MERCHANT_EXPIRIES -> Attribution.MERCHANT_EXPIRY

            // A sale refunded because its merchant couldn't pay it out (2026-11-23).
            state == OrderState.REFUNDED && reason in MERCHANT_FAILURES -> Attribution.MERCHANT_FAILURE

            state == OrderState.REFUNDED && reason in MERCHANT_EXPIRIES -> Attribution.MERCHANT_EXPIRY

            else -> Attribution.NOT_COUNTED
        }

    /** Whether an order that finished in [state] for [reason] enters a merchant's rates. */
    fun isCounted(state: OrderState, reason: String?): Boolean = attribution(state, reason) != Attribution.NOT_COUNTED

    /**
     * The statistics of a window of [orders] (the merchant's last counted ones), with [notCounted] other finished
     * orders beside them. An order in [orders] that isn't counted after all is added to those.
     */
    fun of(orders: List<FinishedOrder>, notCounted: Int = 0): MerchantStats {
        val by = orders.groupingBy { attribution(it.state, it.reason) }.eachCount()
        val times =
            orders
                .filter { it.state == OrderState.COMPLETED }
                .mapNotNull { it.handlingSeconds }
                .filter { it >= 0 }
                .sorted()
        return MerchantStats(
            finished = orders.size + notCounted,
            completed = by[Attribution.COMPLETED] ?: 0,
            merchantFailures = by[Attribution.MERCHANT_FAILURE] ?: 0,
            merchantExpiries = by[Attribution.MERCHANT_EXPIRY] ?: 0,
            reversals = 0,
            medianHandlingSeconds = percentile(times, MEDIAN),
            p90HandlingSeconds = percentile(times, P90),
        )
    }

    /** Nearest rank: the smallest value at least [share] of the values are at or below. */
    fun percentile(sorted: List<Long>, share: Double): Long? {
        if (sorted.isEmpty()) return null
        val rank = ceil(share * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private const val MEDIAN = 0.5
    private const val P90 = 0.9
}
