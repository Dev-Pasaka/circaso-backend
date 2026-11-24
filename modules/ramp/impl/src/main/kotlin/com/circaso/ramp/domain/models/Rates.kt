package com.circaso.ramp.domain.models

import com.circaso.shared.Network
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

/**
 * Where a network's rates come from (decision 2026-11-24, Pasaka). `REFERENCE_SPREAD`: the day's reference rate with
 * staff's spread on each side. `MANUAL`: exact buy and sell rates staff typed, until they expire.
 */
enum class RateMode { REFERENCE_SPREAD, MANUAL }

/** Why a side can't be quoted right now; quoting stops with `ramp.pricing_unavailable`, never a silent fallback. */
enum class RatePause { NO_REFERENCE, REFERENCE_STALE, MANUAL_MISSING, MANUAL_EXPIRED }

/** The reference rate: shillings per US dollar (USDC counted as one dollar), as its source published it at [asOf]. */
data class ReferenceRate(val rate: BigDecimal, val asOf: Instant) {
    fun age(now: Instant): Duration = Duration.between(asOf, now).coerceAtLeast(Duration.ZERO)
}

/**
 * How a quote's rate was made, kept on the quote and its order: [source], the reference rate read at the time (null
 * when none could be read: a manual rate needs none to quote), and the margin over it in basis points: the spread
 * set, or for a manual rate the margin it works out to (signed; null without a reference).
 */
data class RateBasis(val source: RateMode, val referenceRate: BigDecimal?, val spreadBps: Int?)

/** One side's rate in force: shillings per USDC, and how it was made. */
data class AppliedRate(val rate: BigDecimal, val basis: RateBasis)

/** What a side reads as now: its rate, or why it is paused. */
sealed interface RateReading {
    data class Ready(val applied: AppliedRate) : RateReading

    data class Paused(val why: RatePause) : RateReading
}

/**
 * One network's pricing settings (`ramp.pricing_settings`), staff-edited on the ops gateway. Buying (the user pays
 * shillings for USDC) is quoted at the **buy** rate, selling at the **sell** rate; the buy rate is never below the
 * sell rate, so the merchant never loses on the round trip. Spreads default to 0: the reference rate on both sides.
 */
data class PricingSettings(
    val network: Network,
    val mode: RateMode = RateMode.REFERENCE_SPREAD,
    /** Buy rate = reference × (1 + buySpreadBps / 10 000). */
    val buySpreadBps: Int = 0,
    /** Sell rate = reference × (1 − sellSpreadBps / 10 000). */
    val sellSpreadBps: Int = 0,
    /** `MANUAL`: the exact rates (shillings per USDC) and when they stop. */
    val manualBuyRate: BigDecimal? = null,
    val manualSellRate: BigDecimal? = null,
    val manualExpiresAt: Instant? = null,
    /** How far a manual rate may be from the reference when it is saved. */
    val maxDeviationBps: Int = DEFAULT_MAX_DEVIATION_BPS,
    /** A reference older than this pauses reference-based quoting. */
    val maxReferenceAgeHours: Int = DEFAULT_MAX_REFERENCE_AGE_HOURS,
    val version: Long = 0,
    val updatedBy: String? = null,
    val reason: String? = null,
    val updatedAt: Instant = Instant.EPOCH,
) {
    val maxReferenceAge: Duration get() = Duration.ofHours(maxReferenceAgeHours.toLong())

    /** The field that is out of range and what is wrong with it, or null. Manual rates are checked only in `MANUAL`. */
    fun problem(now: Instant): Pair<String, String>? =
        when {
            buySpreadBps !in 0..MAX_SPREAD_BPS -> {
                "buySpreadBps" to SPREAD_RANGE
            }

            sellSpreadBps !in 0..MAX_SPREAD_BPS -> {
                "sellSpreadBps" to SPREAD_RANGE
            }

            maxDeviationBps !in 1..MAX_DEVIATION_BPS -> {
                "maxDeviationBps" to "must be 1 to $MAX_DEVIATION_BPS"
            }

            maxReferenceAgeHours !in 1..MAX_REFERENCE_AGE_HOURS -> {
                "maxReferenceAgeHours" to "must be 1 to $MAX_REFERENCE_AGE_HOURS"
            }

            mode == RateMode.MANUAL -> {
                manualProblem(now)
            }

            else -> {
                null
            }
        }

    private fun manualProblem(now: Instant): Pair<String, String>? {
        val buy = manualBuyRate
        val sell = manualSellRate
        val expires = manualExpiresAt
        return when {
            buy == null -> "manualBuyRate" to "is required in manual mode"
            sell == null -> "manualSellRate" to "is required in manual mode"
            !rateShape(buy) -> "manualBuyRate" to RATE_SHAPE
            !rateShape(sell) -> "manualSellRate" to RATE_SHAPE
            buy < sell -> "manualBuyRate" to "must not be below the sell rate"
            expires == null -> "manualExpiresAt" to "is required in manual mode"
            !expires.isAfter(now) -> "manualExpiresAt" to "must be in the future"
            expires.isAfter(now.plus(MAX_MANUAL_LIFETIME)) -> "manualExpiresAt" to "must be within 30 days"
            else -> null
        }
    }

    /** A manual rate outside the band around [reference]: the field and why, or null when both are inside. */
    fun bandProblem(reference: BigDecimal): Pair<String, String>? {
        val buy = manualBuyRate ?: return null
        val sell = manualSellRate ?: return null
        val limit = "must be within ${percent(maxDeviationBps)} of the reference rate ${reference.toPlainString()}"
        return when {
            deviationBps(buy, reference) > maxDeviationBps -> "manualBuyRate" to limit
            deviationBps(sell, reference) > maxDeviationBps -> "manualSellRate" to limit
            else -> null
        }
    }

    companion object {
        const val DEFAULT_MAX_DEVIATION_BPS = 500
        const val DEFAULT_MAX_REFERENCE_AGE_HOURS = 36
        const val DEFAULT_MANUAL_HOURS = 24L
        const val MAX_SPREAD_BPS = 2_000
        const val MAX_DEVIATION_BPS = 5_000
        const val MAX_REFERENCE_AGE_HOURS = 168
        const val MAX_RATE_DECIMALS = 6
        val MAX_MANUAL_LIFETIME: Duration = Duration.ofDays(30)
        private const val SPREAD_RANGE = "must be 0 to $MAX_SPREAD_BPS basis points"
        private const val RATE_SHAPE = "must be above 0 with at most $MAX_RATE_DECIMALS decimals"
        private val BPS = BigDecimal(10_000)

        fun defaults(network: Network, at: Instant): PricingSettings =
            PricingSettings(network = network, version = 1, updatedAt = at)

        private fun rateShape(rate: BigDecimal): Boolean =
            rate.signum() > 0 && rate.stripTrailingZeros().scale() <= MAX_RATE_DECIMALS

        /** How far [rate] is from [reference], in basis points of the reference, rounded up. */
        fun deviationBps(rate: BigDecimal, reference: BigDecimal): Int =
            rate
                .subtract(reference)
                .abs()
                .multiply(BPS)
                .divide(reference, 0, RoundingMode.CEILING)
                .min(BigDecimal(Int.MAX_VALUE))
                .toInt()

        private fun percent(bps: Int): String =
            BigDecimal(bps).movePointLeft(2).stripTrailingZeros().toPlainString() + "%"
    }
}

/** A change of a network's pricing settings: the row before and after. */
data class PricingChange(val before: PricingSettings, val after: PricingSettings)

/**
 * What a network's rates are now, for staff: the settings, the reference as last read (null when none can be read),
 * whether it is too old for reference-based quoting, and each side's rate or why it is paused.
 */
data class RatesView(
    val settings: PricingSettings,
    val reference: ReferenceRate?,
    val referenceStale: Boolean,
    val buy: RateReading,
    val sell: RateReading,
    val at: Instant,
)
