package com.circaso.ramp.api

import com.circaso.shared.Network
import com.circaso.shared.Resource
import java.time.Instant
import java.util.UUID

// The merchant engine for staff (decision 2026-11-23), for the ops module behind the ops gateway only: the engine's
// settings per network and direction, the log of every routing ("why this merchant"), a simulation of a draft of
// the settings, a merchant's type and limit, and a merchant's ranking signals. Nothing here carries a credential or
// a payer's number.

/** The directions a setting or a routing is for, as these calls take them. */
object RampDirections {
    val ALL: List<String> = listOf("ON_RAMP", "OFF_RAMP")
}

/** How the engine picks: `ROUND_ROBIN` (nothing scored), `SHADOW` (scores and logs, round-robin picks), `ACTIVE`. */
object RampEngineModes {
    val ALL: List<String> = listOf("ROUND_ROBIN", "SHADOW", "ACTIVE")
}

/** What a merchant is, set by staff: it decides the order limit they start with. */
object RampMerchantTypes {
    val ALL: List<String> = listOf("INDIVIDUAL", "BUSINESS")
}

/**
 * The engine's settings for one network and direction: every number it uses. Quality is
 * `Q = wc·C + ws·S + wf·(1 − F)`; a merchant is drawn with the weight
 * `max(Q, floor)^alpha × (1 + hunger) × (1 + newcomer) × sizeFit`. Amounts are KES minor units.
 */
data class OpsEngineSettings(
    /** One of [RampEngineModes.ALL]. */
    val mode: String,
    /** wc, ws, wf: they add up to 1. */
    val weightCompletion: Double,
    val weightSpeed: Double,
    val weightFailure: Double,
    /** k: how many imaginary orders at the prior every merchant starts with. */
    val priorStrength: Double,
    /** p0, and the priors of the failure rate and of the speed score. */
    val completionPrior: Double,
    val failurePrior: Double,
    val speedPrior: Double,
    /** The least quality anyone is drawn with (above zero: nobody eligible is starved). */
    val qualityFloor: Double,
    val alpha: Double,
    /** The most a wait since the last order adds, reached after [hungerWindowMinutes]. */
    val hungerMax: Double,
    val hungerWindowMinutes: Int,
    /** The boost of a merchant with no trades, gone at [newcomerTrades] completed trades. */
    val newcomerMax: Double,
    val newcomerTrades: Int,
    /** Orders up to this fit every merchant alike. */
    val smallOrderMinor: Long,
    /** How many times the order a merchant's free float should hold to fit it fully. */
    val floatCover: Double,
    /** The share of the free float one order may take before [sharePenalty] multiplies the fit. */
    val shareThreshold: Double,
    val sharePenalty: Double,
    /** The handling time promised (the speed score is zero at twice this) and the one that scores full marks. */
    val slaSeconds: Int,
    val targetSeconds: Int,
    /** The statistics window: the last [statsOrders] finished orders within [statsDays] days. */
    val statsOrders: Int,
    val statsDays: Int,
    /** How many merchants one purchase may be tried with. */
    val maxAttempts: Int,
    /** The most one order may be for a merchant of each type, unless staff set their own limit. */
    val individualLimitMinor: Long,
    val businessLimitMinor: Long,
    /** Days a routing decision is kept. */
    val logRetentionDays: Int,
)

/** One version of a network and direction's engine settings, with who wrote it and why (the seed has neither). */
data class OpsEngineSettingsVersion(
    val network: Network,
    val direction: String,
    val version: Long,
    val settings: OpsEngineSettings,
    val updatedBy: String?,
    val reason: String?,
    val updatedAt: Instant,
)

data class OpsEngineSettingsChange(val before: OpsEngineSettingsVersion, val after: OpsEngineSettingsVersion)

/**
 * A merchant's recent record on one network and direction. [finished] is every finished order in the window;
 * [counted] is the ones a rate is taken over: [completed] and what counts against the merchant ([merchantFailures],
 * [merchantExpiries], [reversals]). [notCounted] is the rest: the buyer's doing, or nobody's the merchant could help.
 */
data class OpsMerchantStats(
    val finished: Int,
    val completed: Int,
    val merchantFailures: Int,
    val merchantExpiries: Int,
    val reversals: Int,
    val counted: Int,
    val notCounted: Int,
    val medianHandlingSeconds: Long?,
    val p90HandlingSeconds: Long?,
)

/** How a merchant scored in a routing: every factor of the weight. */
data class OpsRoutingScore(
    val completion: Double,
    val speed: Double,
    val failure: Double,
    val quality: Double,
    val hunger: Double,
    val newcomer: Double,
    val sizeFit: Double,
    val weight: Double,
)

/**
 * One merchant looked at in a routing. [excluded] is why they were left out (`NOT_APPROVED`, `KYC_REQUIRED`,
 * `OFFLINE`, `NO_KEYS`, `PAYMENTS_ISSUE`, `DIRECTION_OFF`, `OVER_LIMIT`, `FLOAT_UNKNOWN`, `FLOAT_SHORT`), null when
 * they were eligible. [score] is null when they were left out or the mode didn't score. [floatMinor] is free float
 * in the unit the order needed (USDC minor units on an on-ramp), null when it wasn't read.
 */
data class OpsRoutingCandidate(
    val merchantId: MerchantId,
    val excluded: String?,
    val approved: Boolean,
    val kycCleared: Boolean,
    val online: Boolean,
    val hasKeys: Boolean,
    val paymentsIssue: Boolean,
    val offersDirection: Boolean,
    val limitMinor: Long,
    val floatMinor: Long?,
    val lastAssignedAt: Instant?,
    val completedTrades: Int,
    val stats: OpsMerchantStats?,
    val score: OpsRoutingScore?,
)

/** One step of a routing's failover: `prompted`, or why it moved on or stopped. */
data class OpsRoutingStep(
    val merchantId: MerchantId,
    val orderId: RampOrderId?,
    val result: String,
    val at: Instant,
)

/**
 * One routing. [ranked] is the failover order used; [engineRanked] the engine's own weighted draw (null when the mode
 * didn't score; the same as [ranked] in `ACTIVE`). [outcome]: `PENDING`, `ROUTED`, `NO_CANDIDATE`, `EXHAUSTED`,
 * `STOPPED`. [merchantId] and [orderId] are who ended up with the order, null when nobody did.
 */
data class OpsRoutingDecision(
    val decisionId: UUID,
    val quoteId: UUID,
    val orderId: RampOrderId?,
    val merchantId: MerchantId?,
    val network: Network,
    val direction: String,
    val fiatAmountMinor: Long,
    val needMinor: Long,
    val settingsVersion: Long,
    val mode: String,
    val outcome: String,
    val candidates: List<OpsRoutingCandidate>,
    val ranked: List<MerchantId>,
    val engineRanked: List<MerchantId>?,
    val steps: List<OpsRoutingStep>,
    val createdAt: Instant,
)

/** Where a page of decisions continues: after the decision made at [at] with [decisionId]. */
data class OpsRoutingCursor(val at: Instant, val decisionId: UUID)

/** Which decisions to list: any merchant, order or time when the field is null. [from] inclusive, [to] exclusive. */
data class OpsRoutingQuery(
    val network: Network,
    val merchantId: MerchantId? = null,
    val orderId: RampOrderId? = null,
    val from: Instant? = null,
    val to: Instant? = null,
)

/** What to simulate: [draft] (null: the settings in force) over the last [lastOrders] real orders, or one [fiatMinor]. */
data class OpsSimulationCommand(
    val network: Network,
    val direction: String,
    val draft: OpsEngineSettings?,
    val lastOrders: Int?,
    val fiatMinor: Long?,
)

/** What one merchant would get: [share] under the draft, [currentShare] under what is in force (both 0 to 1). */
data class OpsSimulatedShare(
    val merchantId: MerchantId,
    val displayName: String,
    val share: Double,
    val currentShare: Double,
    val eligibleOrders: Int,
    /** Why they were left out, and of how many of the replayed orders. */
    val exclusions: Map<String, Int>,
    val quality: Double?,
)

data class OpsSimulation(
    val network: Network,
    val direction: String,
    val mode: String,
    val currentMode: String,
    val orders: Int,
    val unroutable: Int,
    val shares: List<OpsSimulatedShare>,
)

/** A merchant's signals in one direction, with the settings they are measured against. */
data class OpsMerchantPerformance(
    val direction: String,
    val offered: Boolean,
    val orderLimitMinor: Long,
    val stats: OpsMerchantStats,
    val trades: Int,
    val newcomerTrades: Int,
    val newcomerTradesLeft: Int,
    val targetSeconds: Int,
    val slaSeconds: Int,
    val statsOrders: Int,
    val statsDays: Int,
)

data class OpsMerchantSignals(
    val merchantId: MerchantId,
    val network: Network,
    val merchantType: String,
    val directions: List<OpsMerchantPerformance>,
)

/**
 * Staff's view of the merchant engine. Errors: `ramp.settings_invalid` (400, the field named in the details),
 * `ramp.settings_changed` (409), `ramp.routing_not_found` (404), `ramp.merchant_not_found` (404), and
 * `request.malformed` for a direction, mode or type that isn't one.
 */
interface RampEngineAdmin {
    /** The settings in force for [network] and [direction] (one of [RampDirections.ALL]). */
    suspend fun settings(network: Network, direction: String): Resource<OpsEngineSettingsVersion>

    /**
     * Replaces them with the next version. With [version], only when the settings are still at it. Kept with [by]
     * and [reason]. In force everywhere within ten seconds, with no restart.
     */
    suspend fun updateSettings(
        network: Network,
        direction: String,
        settings: OpsEngineSettings,
        version: Long?,
        reason: String,
        by: UUID,
    ): Resource<OpsEngineSettingsChange>

    /** Routing decisions, newest first; [after] is the last decision of the previous page. */
    suspend fun decisions(
        query: OpsRoutingQuery,
        after: OpsRoutingCursor?,
        limit: Int,
    ): Resource<List<OpsRoutingDecision>>

    /** One routing in full: why this merchant. */
    suspend fun decision(decisionId: UUID): Resource<OpsRoutingDecision>

    /** What a draft of the settings would do to the last real orders. Reads only: nothing is written or sent. */
    suspend fun simulate(request: OpsSimulationCommand): Resource<OpsSimulation>

    /**
     * Sets a merchant's type (one of [RampMerchantTypes.ALL]) and their own order limit in KES minor units (null: the
     * type's default from the engine settings).
     */
    suspend fun setMerchantTier(
        merchantId: MerchantId,
        merchantType: String,
        orderLimitMinor: Long?,
        by: UUID,
    ): Resource<OpsMerchant>

    /** The merchant's ranking signals on [network]: what the engine scores them on, per direction. */
    suspend fun merchantSignals(merchantId: MerchantId, network: Network): Resource<OpsMerchantSignals>
}
