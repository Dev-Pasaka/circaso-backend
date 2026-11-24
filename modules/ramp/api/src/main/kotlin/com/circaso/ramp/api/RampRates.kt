package com.circaso.ramp.api

import com.circaso.shared.Network
import com.circaso.shared.Resource
import java.time.Instant
import java.util.UUID

// The ramp's rates for staff (decision 2026-11-24, Pasaka), for the ops module behind the ops gateway only: each
// network's pricing settings (a spread on each side of the reference rate, or exact manual rates with an expiry) and
// the buy and sell rates in force now. Rates are KES per USDC, decimal strings.

/** Where a network's rates come from. */
object RampRateModes {
    const val REFERENCE_SPREAD = "REFERENCE_SPREAD"
    const val MANUAL = "MANUAL"
    val ALL: List<String> = listOf(REFERENCE_SPREAD, MANUAL)
}

/**
 * One network's pricing settings. Buy rate (the user buys USDC) = reference × (1 + [buySpreadBps] / 10 000); sell rate
 * = reference × (1 − [sellSpreadBps] / 10 000). In `MANUAL` mode the rates are [manualBuyRate] and [manualSellRate]
 * until [manualExpiresAt]. [version], [updatedBy], [reason] and [updatedAt] are the row's; an update ignores them.
 */
data class OpsPricingSettings(
    val network: Network,
    /** One of [RampRateModes.ALL]. */
    val mode: String,
    val buySpreadBps: Int,
    val sellSpreadBps: Int,
    val manualBuyRate: String?,
    val manualSellRate: String?,
    val manualExpiresAt: Instant?,
    val maxDeviationBps: Int,
    val maxReferenceAgeHours: Int,
    val version: Long = 0,
    val updatedBy: String? = null,
    val reason: String? = null,
    val updatedAt: Instant = Instant.EPOCH,
)

data class OpsPricingChange(val before: OpsPricingSettings, val after: OpsPricingSettings)

/**
 * One side's rate now. [rate] is null when the side is paused, and [paused] says why: `NO_REFERENCE`,
 * `REFERENCE_STALE`, `MANUAL_MISSING` or `MANUAL_EXPIRED`. [spreadBps] is the margin over the reference (the spread
 * set, or what a manual rate works out to; null without a reference).
 */
data class OpsSideRate(
    val rate: String?,
    val source: String,
    val spreadBps: Int?,
    val paused: String?,
)

/** A network's rates now: the reference as last read, its age, and each side's rate or why it is paused. */
data class OpsRates(
    val network: Network,
    val mode: String,
    val referenceRate: String?,
    val referenceAsOf: Instant?,
    val referenceAgeSeconds: Long?,
    val referenceStale: Boolean,
    val maxReferenceAgeHours: Int,
    val maxDeviationBps: Int,
    val manualExpiresAt: Instant?,
    val buy: OpsSideRate,
    val sell: OpsSideRate,
    val at: Instant,
)

/**
 * Staff's view of the ramp's rates. Errors: `ramp.settings_invalid` (400, the field and the problem named in the
 * details), `ramp.settings_changed` (409), and `request.malformed` for a mode or rate that isn't one.
 */
interface RampRatesAdmin {
    suspend fun pricing(network: Network): Resource<OpsPricingSettings>

    /**
     * Replaces [settings]' network's pricing settings with the next version; with [version], only when they are still
     * at it. Manual rates need the buy rate at or above the sell rate and both within `maxDeviationBps` of the
     * reference rate now; `manualExpiresAt` left out is 24 hours from now. In force everywhere within ten seconds.
     */
    suspend fun updatePricing(
        settings: OpsPricingSettings,
        version: Long?,
        reason: String,
        by: UUID,
    ): Resource<OpsPricingChange>

    suspend fun rates(network: Network): Resource<OpsRates>
}
