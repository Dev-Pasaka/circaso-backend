package com.circaso.ramp.domain.models

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.QuoteId
import com.circaso.ramp.api.RampOrderId
import com.circaso.shared.Network
import java.time.Instant
import java.util.UUID
import kotlin.math.abs

/**
 * How the engine picks. `ROUND_ROBIN`: the least recently assigned eligible merchant, nothing scored. `SHADOW`: the
 * engine scores, ranks and logs, but round-robin still picks (to watch the engine before trusting it). `ACTIVE`: the
 * engine's ranking is the failover order. Eligibility is the same hard filter in all three.
 */
enum class EngineMode { ROUND_ROBIN, SHADOW, ACTIVE }

/** What a merchant is, set by staff: it decides the order limit they start with. */
enum class MerchantType { INDIVIDUAL, BUSINESS }

/**
 * The engine's settings for one network and direction (decision 2026-11-23): every weight, prior, boost, window, size
 * band, tier limit and time it uses, staff-edited on the ops gateway with no redeploy. Kept as a versioned document
 * (`ramp.engine_settings`); [EngineSettings.problem] is the validation every change passes first.
 *
 * Quality is `Q = wc·C + ws·S + wf·(1 − F)`; the weight a merchant is drawn with is
 * `max(Q, floor)^alpha × (1 + hunger) × (1 + newcomer) × sizeFit` (see `Scoring`).
 */
data class EngineSettings(
    val mode: EngineMode,
    /** wc, ws, wf: how much completion, speed and (the absence of) failures count. They add up to 1. */
    val weightCompletion: Double,
    val weightSpeed: Double,
    val weightFailure: Double,
    /** k: how many imaginary orders at the prior every merchant starts with, so a few real ones don't swing a rate. */
    val priorStrength: Double,
    /** p0: the completion rate assumed before there is data. */
    val completionPrior: Double,
    /** The failure rate assumed before there is data. */
    val failurePrior: Double,
    /** The speed score of a merchant with no completed order to time. */
    val speedPrior: Double,
    /** The least quality anyone is drawn with: above zero, so nobody eligible is ever starved. */
    val qualityFloor: Double,
    /** How sharply quality separates merchants: 1 is proportional, 0 ignores quality, above 1 favours the best. */
    val alpha: Double,
    /** hMax: the most the weight grows for a merchant who has waited [hungerWindowMinutes] since their last order. */
    val hungerMax: Double,
    val hungerWindowMinutes: Int,
    /** nMax: the boost of a merchant with no trades, fading to nothing at [newcomerTrades] completed trades. */
    val newcomerMax: Double,
    val newcomerTrades: Int,
    /** Orders up to this (KES minor units) fit every merchant alike. */
    val smallOrderMinor: Long,
    /** How many times the order a merchant's free float should hold to fit it fully. */
    val floatCover: Double,
    /** The share of a merchant's free float one order may take before [sharePenalty] applies. */
    val shareThreshold: Double,
    /** What the size fit is multiplied by when one order takes more than [shareThreshold] of the float. */
    val sharePenalty: Double,
    /** The handling time promised: the speed score reaches zero at twice this. */
    val slaSeconds: Int,
    /** The handling time that scores full marks. */
    val targetSeconds: Int,
    /** The statistics window: a merchant's last [statsOrders] counted orders within [statsDays] days. */
    val statsOrders: Int,
    val statsDays: Int,
    /** How many merchants one purchase may be tried with before giving up. */
    val maxAttempts: Int,
    /** The most one order may be (KES minor units) for a merchant of each type, unless staff set their own limit. */
    val individualLimitMinor: Long,
    val businessLimitMinor: Long,
    /** How long a routing decision is kept before `ramp.prune-routing` deletes it. */
    val logRetentionDays: Int,
) {
    /** The tier's limit for [type]. */
    fun tierLimit(type: MerchantType): Long =
        when (type) {
            MerchantType.INDIVIDUAL -> individualLimitMinor
            MerchantType.BUSINESS -> businessLimitMinor
        }

    /** The first thing wrong with these settings, as the field and what it must be; null when they are valid. */
    fun problem(): Pair<String, String>? = weightProblem() ?: boostProblem() ?: sizeProblem() ?: windowProblem()

    private fun weightProblem(): Pair<String, String>? =
        when {
            !unit(weightCompletion) -> "weightCompletion" to UNIT

            !unit(weightSpeed) -> "weightSpeed" to UNIT

            !unit(weightFailure) -> "weightFailure" to UNIT

            !sumsToOne() -> "weightCompletion" to "the three weights must add up to 1"

            !within(priorStrength, 0.0, MAX_PRIOR_STRENGTH) -> "priorStrength" to "must be 0 to 1000"

            !unit(completionPrior) -> "completionPrior" to UNIT

            !unit(failurePrior) -> "failurePrior" to UNIT

            !unit(speedPrior) -> "speedPrior" to UNIT

            // Above zero: a floor of zero could give an eligible merchant no chance at all.
            !within(qualityFloor, MIN_POSITIVE, 1.0) -> "qualityFloor" to "must be 0.001 to 1"

            !within(alpha, 0.0, MAX_ALPHA) -> "alpha" to "must be 0 to 5"

            else -> null
        }

    private fun boostProblem(): Pair<String, String>? =
        when {
            !within(hungerMax, 0.0, MAX_BOOST) -> "hungerMax" to "must be 0 to 10"
            hungerWindowMinutes !in 1..MAX_HUNGER_MINUTES -> "hungerWindowMinutes" to "must be 1 to 10080"
            !within(newcomerMax, 0.0, MAX_BOOST) -> "newcomerMax" to "must be 0 to 10"
            newcomerTrades !in 1..MAX_NEWCOMER_TRADES -> "newcomerTrades" to "must be 1 to 10000"
            else -> null
        }

    private fun sizeProblem(): Pair<String, String>? =
        when {
            smallOrderMinor < 0 -> "smallOrderMinor" to "can't be negative"

            !within(floatCover, 1.0, MAX_COVER) -> "floatCover" to "must be 1 to 100"

            !within(shareThreshold, MIN_POSITIVE, 1.0) -> "shareThreshold" to "must be 0.001 to 1"

            // Above zero, for the same reason as the floor.
            !within(sharePenalty, MIN_POSITIVE, 1.0) -> "sharePenalty" to "must be 0.001 to 1"

            individualLimitMinor <= 0 -> "individualLimitMinor" to "must be above zero"

            businessLimitMinor <= 0 -> "businessLimitMinor" to "must be above zero"

            else -> null
        }

    private fun windowProblem(): Pair<String, String>? =
        when {
            targetSeconds !in 1..MAX_SECONDS -> "targetSeconds" to "must be 1 to 86400"
            slaSeconds !in targetSeconds..MAX_SECONDS -> "slaSeconds" to "must be at least the target, at most 86400"
            statsOrders !in 1..MAX_STATS_ORDERS -> "statsOrders" to "must be 1 to 500"
            statsDays !in 1..MAX_DAYS -> "statsDays" to "must be 1 to 365"
            maxAttempts !in 1..MAX_ATTEMPTS -> "maxAttempts" to "must be 1 to 10"
            logRetentionDays !in 1..MAX_RETENTION_DAYS -> "logRetentionDays" to "must be 1 to 3650"
            else -> null
        }

    private fun sumsToOne(): Boolean = abs(weightCompletion + weightSpeed + weightFailure - 1.0) <= WEIGHT_TOLERANCE

    private fun unit(value: Double): Boolean = within(value, 0.0, 1.0)

    private fun within(
        value: Double,
        least: Double,
        most: Double,
    ): Boolean = !value.isNaN() && value >= least && value <= most

    companion object {
        private const val UNIT = "must be 0 to 1"
        private const val WEIGHT_TOLERANCE = 0.000_001
        private const val MIN_POSITIVE = 0.001
        private const val MAX_PRIOR_STRENGTH = 1_000.0
        private const val MAX_ALPHA = 5.0
        private const val MAX_BOOST = 10.0
        private const val MAX_COVER = 100.0
        private const val MAX_HUNGER_MINUTES = 10_080
        private const val MAX_NEWCOMER_TRADES = 10_000
        private const val MAX_SECONDS = 86_400
        private const val MAX_STATS_ORDERS = 500
        private const val MAX_DAYS = 365
        private const val MAX_ATTEMPTS = 10
        private const val MAX_RETENTION_DAYS = 3_650

        /**
         * What a network and direction start with, written once when their row is missing (decision 2026-11-23):
         * the engine picks on the testnet and only watches on mainnet until staff switch it on.
         */
        fun defaults(network: Network): EngineSettings =
            EngineSettings(
                mode = if (network == Network.TESTNET) EngineMode.ACTIVE else EngineMode.SHADOW,
                weightCompletion = 0.5,
                weightSpeed = 0.3,
                weightFailure = 0.2,
                priorStrength = 10.0,
                completionPrior = 0.9,
                failurePrior = 0.1,
                speedPrior = 0.9,
                qualityFloor = 0.1,
                alpha = 1.0,
                hungerMax = 1.0,
                hungerWindowMinutes = 120,
                newcomerMax = 0.5,
                newcomerTrades = 20,
                smallOrderMinor = 200_000,
                floatCover = 3.0,
                shareThreshold = 0.5,
                sharePenalty = 0.25,
                slaSeconds = 900,
                targetSeconds = 300,
                statsOrders = 50,
                statsDays = 30,
                maxAttempts = 3,
                individualLimitMinor = 3_000_000,
                businessLimitMinor = 15_000_000,
                logRetentionDays = 90,
            )
    }
}

/** One version of a network and direction's engine settings, and who wrote it. */
data class EngineSettingsVersion(
    val network: Network,
    val direction: Direction,
    val version: Long,
    val settings: EngineSettings,
    val updatedBy: String?,
    val reason: String?,
    val updatedAt: Instant,
)

data class EngineSettingsChange(val before: EngineSettingsVersion, val after: EngineSettingsVersion)

/** How a finished order counts in its merchant's statistics. */
enum class Attribution {
    COMPLETED,

    /** It ended because of the merchant: their provider refused, their balance there was empty, they were offline. */
    MERCHANT_FAILURE,

    /** It ran out of time because of the merchant (a payout they never made). */
    MERCHANT_EXPIRY,

    /** The buyer's doing, or nobody's the merchant could help: never counted for or against the merchant. */
    NOT_COUNTED,
}

/** One finished order as the statistics read it from the timeline. */
data class FinishedOrder(
    val orderId: RampOrderId,
    val state: OrderState,
    val reason: String?,
    val finishedAt: Instant,
    /** From the payment being verified (or, selling, the funds arriving) to completion; null when not both are known. */
    val handlingSeconds: Long?,
)

/**
 * A merchant's recent record on one network and direction. The window is their last **counted** orders within the
 * settings' days: [counted] (completed, merchant failures, merchant expiries and reversals), which alone enter a
 * rate. [notCounted] is every other order of theirs that finished in those days (a buyer who ignored or cancelled the
 * prompt, the provider out of reach): shown, never scored. [finished] is the two together.
 */
data class MerchantStats(
    val finished: Int,
    val completed: Int,
    val merchantFailures: Int,
    val merchantExpiries: Int,
    /** M-Pesa reversals after release: always 0 until reversal reports are built. */
    val reversals: Int,
    val medianHandlingSeconds: Long?,
    val p90HandlingSeconds: Long?,
) {
    /** The orders a rate is taken over. */
    val counted: Int get() = completed + failed

    /** Everything that counts against the merchant. */
    val failed: Int get() = merchantFailures + merchantExpiries + reversals

    /** Finished orders that are nobody's record: the buyer's doing, or the platform's or the provider's. */
    val notCounted: Int get() = finished - counted

    companion object {
        val NONE = MerchantStats(0, 0, 0, 0, 0, null, null)
    }
}

/** Why a merchant was left out of a routing. The first that applies, in this order. */
enum class Exclusion {
    NOT_APPROVED,
    KYC_REQUIRED,

    /** No trading window, or one that ends before the order's payment window does. */
    OFFLINE,

    /** This node doesn't hold their payment keys, or holds keys for another provider than the network's. */
    NO_KEYS,

    /** Their provider refused a prompt for something only they can fix. */
    PAYMENTS_ISSUE,

    /** They don't offer this direction. */
    DIRECTION_OFF,

    /** The order is above their limit. */
    OVER_LIMIT,

    /** Their float couldn't be read. */
    FLOAT_UNKNOWN,

    /** Their free float doesn't cover the order. */
    FLOAT_SHORT,
}

/** What is being routed. [needMinor] is what the merchant's float must cover, in the float's own unit. */
data class RoutingOrder(
    val direction: Direction,
    /** The order in KES minor units: what limits and the small-order line are compared with. */
    val fiatMinor: Long,
    /** Buying: the USDC to release. Selling: the shillings to pay out. */
    val needMinor: Long,
)

/** Everything the engine knows about one merchant when it routes: the inputs of eligibility and of the score. */
data class CandidateFacts(
    val merchantId: MerchantId,
    val approved: Boolean,
    val kycCleared: Boolean,
    val online: Boolean,
    val hasKeys: Boolean,
    val paymentsIssue: Boolean,
    val offersDirection: Boolean,
    /** The most one order may be for this merchant, KES minor units: their own limit, else their type's. */
    val limitMinor: Long,
    /** Free float in the unit of [RoutingOrder.needMinor]; null when it wasn't read or couldn't be. */
    val floatMinor: Long?,
    val lastAssignedAt: Instant?,
    val completedTrades: Int,
    /** Null when the mode doesn't score. */
    val stats: MerchantStats?,
)

/** How a merchant scored. Every factor is kept, so a decision can be explained from its log alone. */
data class Score(
    val completion: Double,
    val speed: Double,
    val failure: Double,
    val quality: Double,
    val hunger: Double,
    val newcomer: Double,
    val sizeFit: Double,
    val weight: Double,
)

/** One merchant in a routing: left out with a reason, or eligible (and scored, when the mode scores). */
data class RoutedCandidate(val facts: CandidateFacts, val exclusion: Exclusion?, val score: Score?) {
    val isEligible: Boolean get() = exclusion == null
}

/**
 * What the engine decided for one order: every merchant looked at, and the failover order. [ranked] is the order
 * used; [engineRanked] is the engine's own weighted draw (null when nothing was scored), which is [ranked] itself in
 * `ACTIVE` and only logged in `SHADOW`.
 */
data class RoutingPlan(
    val mode: EngineMode,
    val candidates: List<RoutedCandidate>,
    val ranked: List<MerchantId>,
    val engineRanked: List<MerchantId>?,
) {
    val pick: MerchantId? get() = ranked.firstOrNull()
}

/** How a routing ended. */
enum class RoutingOutcome {
    /** Written before the first attempt; a row left like this is a request that died halfway. */
    PENDING,

    /** A merchant took the order and the prompt went out. */
    ROUTED,

    /** Nobody was eligible. */
    NO_CANDIDATE,

    /** Every merchant tried, up to the settings' attempts, couldn't take it. */
    EXHAUSTED,

    /** Stopped on purpose: a prompt may be on the phone, or the order couldn't be written. */
    STOPPED,
}

/** One step of a routing's failover: the merchant tried and how it ended. */
data class RoutingStep(
    val merchantId: MerchantId,
    val orderId: RampOrderId?,
    /** `prompted`, or why it moved on or stopped: `reserve_failed`, `prompt_refused`, `provider_unreachable`, … */
    val result: String,
    val at: Instant,
)

/** One logged routing (`ramp.routing_decisions`). */
data class RoutingDecision(
    val decisionId: UUID,
    val quoteId: QuoteId,
    val orderId: RampOrderId?,
    val merchantId: MerchantId?,
    val network: Network,
    val order: RoutingOrder,
    val settingsVersion: Long,
    val plan: RoutingPlan,
    val outcome: RoutingOutcome,
    val steps: List<RoutingStep>,
    val createdAt: Instant,
)

/** Which routing decisions to read: any merchant, quote or time when the field is null. */
data class RoutingFilter(
    val network: Network,
    val merchantId: MerchantId? = null,
    val quoteId: QuoteId? = null,
    val from: Instant? = null,
    val to: Instant? = null,
)

/** What staff ask the routing log: a merchant's decisions, an order's, a stretch of time, or all of a network's. */
data class RoutingQuery(
    val network: Network,
    val merchantId: MerchantId? = null,
    val orderId: RampOrderId? = null,
    val from: Instant? = null,
    val to: Instant? = null,
)

/** A merchant's ranking signals as they are shown to the merchant themself (and to staff): plain counts and times. */
data class Performance(
    val direction: Direction,
    val offered: Boolean,
    /** The most one order of theirs may be in this direction, KES minor units. */
    val orderLimitMinor: Long,
    val stats: MerchantStats,
    val trades: Int,
    val settings: EngineSettings,
) {
    /** Whether the newcomer boost still applies, and how many completed trades are left of it. */
    val newcomerTradesLeft: Int get() = (settings.newcomerTrades - trades).coerceAtLeast(0)
}

/** RM16: the merchant's own type and signals on a network, per direction. Never another merchant's. */
data class MerchantPerformance(
    val merchantId: MerchantId,
    val network: Network,
    val merchantType: MerchantType,
    val directions: List<Performance>,
)

/** What one merchant would get under a simulated routing. */
data class SimulatedShare(
    val merchantId: MerchantId,
    val displayName: String,
    /** The part of the replayed orders they would be picked first for, under the draft and under what is in force. */
    val share: Double,
    val currentShare: Double,
    /** How many of the replayed orders they were eligible for. */
    val eligibleOrders: Int,
    /** Why they were left out, and of how many orders. */
    val exclusions: Map<Exclusion, Int>,
    /** Their quality under the draft; null when the draft doesn't score, or they were never eligible. */
    val quality: Double?,
)

/** RO: what a draft of the settings would do to the last real orders, with nothing written or sent. */
data class Simulation(
    val network: Network,
    val direction: Direction,
    val mode: EngineMode,
    val currentMode: EngineMode,
    val orders: Int,
    /** Orders nobody could have taken. */
    val unroutable: Int,
    val shares: List<SimulatedShare>,
)

/** What to replay: the last [lastOrders] real orders, or one order of [fiatMinor]. */
data class SimulationCommand(
    val network: Network,
    val direction: Direction,
    val draft: EngineSettings?,
    val lastOrders: Int?,
    val fiatMinor: Long?,
)
