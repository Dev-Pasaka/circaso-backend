package com.circaso.ops.presentation.dto

import kotlinx.serialization.Serializable

/**
 * The merchant engine's settings for one network and direction (decision 2026-11-23). Quality is
 * `Q = weightCompletion·C + weightSpeed·S + weightFailure·(1 − F)`, where `C = (completed + k·p0) / (counted + k)`,
 * `F` is the failure rate smoothed the same way with `failurePrior`, and `S` is 1 while the median handling time is
 * within `targetSeconds`, falling in a straight line to 0 at twice `slaSeconds` (`speedPrior` with nothing timed). A
 * merchant is drawn with the weight `max(Q, qualityFloor)^alpha × (1 + hunger) × (1 + newcomer) × sizeFit`:
 * `hunger = hungerMax × min(1, minutes since their last order / hungerWindowMinutes)`,
 * `newcomer = newcomerMax × max(0, 1 − trades / newcomerTrades)`, and `sizeFit` is 1 up to `smallOrderMinor`, else
 * `min(1, free float / (order × floatCover))`, multiplied by `sharePenalty` when the order takes more than
 * `shareThreshold` of the free float.
 *
 * `mode`: ROUND_ROBIN (nothing scored: the least recently assigned merchant), SHADOW (scored and logged, round-robin
 * still picks), ACTIVE (the weighted draw picks). Amounts (`…Minor`) are KES cents as strings.
 *
 * Ranges: the three weights 0 to 1 and adding up to 1; `priorStrength` 0 to 1000; the three priors 0 to 1;
 * `qualityFloor` 0.001 to 1; `alpha` 0 to 5; `hungerMax` and `newcomerMax` 0 to 10; `hungerWindowMinutes` 1 to 10080;
 * `newcomerTrades` 1 to 10000; `floatCover` 1 to 100; `shareThreshold` and `sharePenalty` 0.001 to 1; `targetSeconds`
 * 1 to 86400 and `slaSeconds` from the target to 86400; `statsOrders` 1 to 500; `statsDays` 1 to 365; `maxAttempts` 1
 * to 10; the two limits above zero; `logRetentionDays` 1 to 3650.
 */
@Serializable
data class RampEngineSettingsDto(
    val mode: String,
    val weightCompletion: Double,
    val weightSpeed: Double,
    val weightFailure: Double,
    val priorStrength: Double,
    val completionPrior: Double,
    val failurePrior: Double,
    val speedPrior: Double,
    val qualityFloor: Double,
    val alpha: Double,
    val hungerMax: Double,
    val hungerWindowMinutes: Int,
    val newcomerMax: Double,
    val newcomerTrades: Int,
    val smallOrderMinor: String,
    val floatCover: Double,
    val shareThreshold: Double,
    val sharePenalty: Double,
    val slaSeconds: Int,
    val targetSeconds: Int,
    val statsOrders: Int,
    val statsDays: Int,
    val maxAttempts: Int,
    val individualLimitMinor: String,
    val businessLimitMinor: String,
    val logRetentionDays: Int,
)

/**
 * `GET /ops/ramp/engine-settings?network=&direction=`: the version in force. `version` rises by one with each change;
 * `updatedBy` (a staff id) and `reason` are null on the seed.
 */
@Serializable
data class RampEngineSettingsResponse(
    val network: String,
    val direction: String,
    val version: Long,
    val settings: RampEngineSettingsDto,
    val updatedBy: String? = null,
    val reason: String? = null,
    val updatedAt: String,
)

/**
 * `PUT /ops/ramp/engine-settings?network=&direction=`: the whole document, the `version` it was read at (a change
 * made meanwhile is refused; left out: save over whatever is there) and why.
 */
@Serializable
data class UpdateRampEngineSettingsRequest(
    val settings: RampEngineSettingsDto,
    val version: Long? = null,
    val reason: String = "",
)

/**
 * `POST /ops/ramp/engine-settings/simulate?network=&direction=`: `settings` is the draft (left out: what is in
 * force). Either `lastOrders` (the last real orders to replay; default 50, at most 500) or `amountMinor` (one order of
 * that many KES cents).
 */
@Serializable
data class RampSimulationRequest(
    val settings: RampEngineSettingsDto? = null,
    val lastOrders: Int? = null,
    val amountMinor: String? = null,
)

/**
 * One merchant in a simulation. `sharePercent` is the part of the replayed orders they would be picked first for
 * under the draft, `currentSharePercent` under the settings in force. `eligibleOrders` is how many of the orders they
 * could have taken; `exclusions` is why they were left out of the others, and of how many. `quality` is their average
 * Q under the draft (null when the draft doesn't score, or they were never eligible).
 */
@Serializable
data class RampSimulatedShareDto(
    val merchantId: String,
    val displayName: String,
    val sharePercent: String,
    val currentSharePercent: String,
    val eligibleOrders: Int,
    val exclusions: Map<String, Int>,
    val quality: Double? = null,
)

/** `orders` were replayed; `unroutable` of them nobody could have taken under the draft. */
@Serializable
data class RampSimulationResponse(
    val network: String,
    val direction: String,
    val mode: String,
    val currentMode: String,
    val orders: Int,
    val unroutable: Int,
    val shares: List<RampSimulatedShareDto>,
)

/**
 * A merchant's recent record. `finished` is every finished order in the window; `counted` is the ones a rate is taken
 * over: `completed`, `merchantFailures` (their provider refused the prompt, their prepaid balance there was empty,
 * they were offline when the prompt had to go out, a payout failed), `merchantExpiries` (a payout they never made)
 * and `reversals` (0 until reversal reports exist). `notCounted` is the rest (a buyer who ignored or cancelled the
 * prompt, the provider out of reach) and never moves a rate. Handling time is from the verified payment to completion.
 */
@Serializable
data class RampMerchantStatsDto(
    val finished: Int,
    val completed: Int,
    val merchantFailures: Int,
    val merchantExpiries: Int,
    val reversals: Int,
    val counted: Int,
    val notCounted: Int,
    val medianHandlingSeconds: Long? = null,
    val p90HandlingSeconds: Long? = null,
)

/** Every factor of a merchant's weight in one routing. */
@Serializable
data class RampRoutingScoreDto(
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
 * One merchant looked at in a routing. `excluded` is why they were left out, null when they were eligible:
 * NOT_APPROVED, KYC_REQUIRED, OFFLINE (no trading window that outlasts the payment window), NO_KEYS (their payment
 * keys aren't unlocked on this node for the network's provider), PAYMENTS_ISSUE, DIRECTION_OFF, OVER_LIMIT,
 * FLOAT_UNKNOWN, FLOAT_SHORT. `floatMinor` is their free float in the unit the order needed (USDC minor units when
 * buying), null when it wasn't read. `score` is null when they were left out or the mode didn't score.
 */
@Serializable
data class RampRoutingCandidateDto(
    val merchantId: String,
    val excluded: String? = null,
    val approved: Boolean,
    val kycCleared: Boolean,
    val online: Boolean,
    val hasKeys: Boolean,
    val paymentsIssue: Boolean,
    val offersDirection: Boolean,
    val limitMinor: String,
    val floatMinor: String? = null,
    val lastAssignedAt: String? = null,
    val completedTrades: Int,
    val stats: RampMerchantStatsDto? = null,
    val score: RampRoutingScoreDto? = null,
)

/**
 * One step of a routing's failover. `result`: `prompted`, or why it moved on (`reserve_failed`, `prompt_refused`,
 * `provider_balance`, `merchant_offline`, `provider_unreachable`, `prompt_failed`) or stopped (`prompt_unconfirmed`,
 * `provider_unavailable`). `orderId` is the attempt's own order, null when none was written.
 */
@Serializable
data class RampRoutingStepDto(
    val merchantId: String,
    val orderId: String? = null,
    val result: String,
    val at: String,
)

/**
 * A routing in a list. `outcome`: PENDING (the request died halfway), ROUTED, NO_CANDIDATE, EXHAUSTED (everybody
 * tried couldn't take it), STOPPED. `merchantId` and `orderId` are who ended up with the order, null when nobody did.
 * `fiatAmountMinor` is KES cents.
 */
@Serializable
data class RampRoutingSummaryDto(
    val decisionId: String,
    val createdAt: String,
    val quoteId: String,
    val orderId: String? = null,
    val merchantId: String? = null,
    val direction: String,
    val fiatAmountMinor: String,
    val mode: String,
    val outcome: String,
    val settingsVersion: Long,
    val candidates: Int,
    val eligible: Int,
    val attempts: Int,
)

@Serializable
data class RampRoutingListResponse(val decisions: List<RampRoutingSummaryDto>, val nextCursor: String? = null)

/**
 * A routing in full: "why this merchant". `ranked` is the failover order that was used; `engineRanked` is the
 * engine's own weighted draw (null when the mode didn't score; the same as `ranked` when ACTIVE; in SHADOW it is what
 * the engine would have done). `needMinor` is what the merchant's float had to cover (USDC minor units when buying).
 */
@Serializable
data class RampRoutingDecisionResponse(
    val decisionId: String,
    val createdAt: String,
    val network: String,
    val direction: String,
    val quoteId: String,
    val orderId: String? = null,
    val merchantId: String? = null,
    val fiatAmountMinor: String,
    val needMinor: String,
    val mode: String,
    val outcome: String,
    val settingsVersion: Long,
    val candidates: List<RampRoutingCandidateDto>,
    val ranked: List<String>,
    val engineRanked: List<String>? = null,
    val steps: List<RampRoutingStepDto>,
)

/**
 * `PUT /ops/ramp/merchants/{merchantId}/tier`: `merchantType` INDIVIDUAL or BUSINESS; `orderLimitMinor` the
 * merchant's own limit in KES cents (left out or null: their type's default from the engine settings); and why.
 */
@Serializable
data class RampMerchantTierRequest(
    val merchantType: String = "",
    val orderLimitMinor: String? = null,
    val reason: String = "",
)

/** A merchant's signals in one direction, with the settings they are measured against. `orderLimitMinor`: KES cents. */
@Serializable
data class RampMerchantPerformanceDto(
    val direction: String,
    val offered: Boolean,
    val orderLimitMinor: String,
    val stats: RampMerchantStatsDto,
    val trades: Int,
    val newcomerTrades: Int,
    val newcomerTradesLeft: Int,
    val targetSeconds: Int,
    val slaSeconds: Int,
    val statsOrders: Int,
    val statsDays: Int,
)

/** `GET /ops/ramp/merchants/{merchantId}/signals?network=`: what the engine scores the merchant on, per direction. */
@Serializable
data class RampMerchantSignalsResponse(
    val merchantId: String,
    val network: String,
    val merchantType: String,
    val directions: List<RampMerchantPerformanceDto>,
)
