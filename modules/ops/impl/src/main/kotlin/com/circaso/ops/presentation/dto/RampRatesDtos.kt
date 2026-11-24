package com.circaso.ops.presentation.dto

import kotlinx.serialization.Serializable

/**
 * `GET /ops/ramp/pricing?network=`: one network's pricing settings. Rates are KES per USDC as decimal strings; times
 * are ISO-8601. `version` rises by one with each change; `updatedBy` (a staff id) and `reason` are null on the seed.
 */
@Serializable
data class RampPricingResponse(
    val network: String,
    val mode: String,
    val buySpreadBps: Int,
    val sellSpreadBps: Int,
    val manualBuyRate: String? = null,
    val manualSellRate: String? = null,
    val manualExpiresAt: String? = null,
    val maxDeviationBps: Int,
    val maxReferenceAgeHours: Int,
    val version: Long,
    val updatedBy: String? = null,
    val reason: String? = null,
    val updatedAt: String,
)

/**
 * `PUT /ops/ramp/pricing?network=`: the whole settings, the `version` they were read at (a change made meanwhile is
 * refused; left out: save over whatever is there) and why. `manualExpiresAt` left out in MANUAL mode is 24 hours from
 * now; the manual fields are kept but ignored in REFERENCE_SPREAD mode.
 */
@Serializable
data class UpdateRampPricingRequest(
    val mode: String,
    val buySpreadBps: Int = 0,
    val sellSpreadBps: Int = 0,
    val manualBuyRate: String? = null,
    val manualSellRate: String? = null,
    val manualExpiresAt: String? = null,
    val maxDeviationBps: Int = 500,
    val maxReferenceAgeHours: Int = 36,
    val version: Long? = null,
    val reason: String = "",
)

/**
 * One side's rate now: `rate` (null when paused), its `source`, the margin over the reference in `spreadBps`, and
 * `paused`: why the side can't be quoted (`NO_REFERENCE`, `REFERENCE_STALE`, `MANUAL_MISSING`, `MANUAL_EXPIRED`).
 */
@Serializable
data class RampSideRateDto(
    val rate: String? = null,
    val source: String,
    val spreadBps: Int? = null,
    val paused: String? = null,
)

/** `GET /ops/ramp/rates?network=`: the reference and its age, and the buy and sell rates in force now. */
@Serializable
data class RampRatesResponse(
    val network: String,
    val mode: String,
    val referenceRate: String? = null,
    val referenceAsOf: String? = null,
    val referenceAgeSeconds: Long? = null,
    val referenceStale: Boolean,
    val maxReferenceAgeHours: Int,
    val maxDeviationBps: Int,
    val manualExpiresAt: String? = null,
    val buy: RampSideRateDto,
    val sell: RampSideRateDto,
    val at: String,
)
