package com.circaso.ramp.domain.support

import com.circaso.ramp.domain.models.AppliedRate
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.PricingSettings
import com.circaso.ramp.domain.models.RateBasis
import com.circaso.ramp.domain.models.RateMode
import com.circaso.ramp.domain.models.RatePause
import com.circaso.ramp.domain.models.RateReading
import com.circaso.ramp.domain.models.ReferenceRate
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * The rate each side is quoted at (decision 2026-11-24, Pasaka), pure. Buying (`ON_RAMP`) is quoted at the buy rate,
 * selling (`OFF_RAMP`) at the sell rate.
 *
 * - `REFERENCE_SPREAD`: buy = reference × (1 + buy spread), sell = reference × (1 − sell spread). A rate with more than
 *   [RATE_DECIMALS] decimals is rounded the merchant's way (buy up, sell down). No reference, or one older than the
 *   settings' `maxReferenceAgeHours`, pauses quoting.
 * - `MANUAL`: staff's exact rates until `manualExpiresAt`; after that quoting pauses until staff set new ones or go back
 *   to the reference. The reference is needed only for the band check when they are saved; when it can be read it is
 *   still recorded on the quote, with the margin the manual rate works out to.
 */
object RatePolicy {
    const val RATE_DECIMALS = 6
    private val BPS = BigDecimal(10_000)

    fun read(
        settings: PricingSettings,
        reference: ReferenceRate?,
        direction: Direction,
        now: Instant,
    ): RateReading =
        when (settings.mode) {
            RateMode.REFERENCE_SPREAD -> spread(settings, reference, direction, now)
            RateMode.MANUAL -> manual(settings, reference, direction, now)
        }

    /** Whether [reference] is too old (or missing) for reference-based quoting under [settings]. */
    fun isStale(
        settings: PricingSettings,
        reference: ReferenceRate?,
        now: Instant,
    ): Boolean = reference == null || reference.age(now) > settings.maxReferenceAge

    private fun spread(
        settings: PricingSettings,
        reference: ReferenceRate?,
        direction: Direction,
        now: Instant,
    ): RateReading {
        if (reference == null || reference.rate.signum() <= 0) return RateReading.Paused(RatePause.NO_REFERENCE)
        if (isStale(settings, reference, now)) return RateReading.Paused(RatePause.REFERENCE_STALE)
        val buying = direction == Direction.ON_RAMP
        val bps = if (buying) settings.buySpreadBps else settings.sellSpreadBps
        val factor = if (buying) BPS.add(BigDecimal(bps)) else BPS.subtract(BigDecimal(bps))
        val exact = reference.rate.multiply(factor).divide(BPS)
        val rate =
            if (exact.scale() <= RATE_DECIMALS) {
                exact
            } else {
                exact.setScale(RATE_DECIMALS, if (buying) RoundingMode.CEILING else RoundingMode.FLOOR)
            }
        return RateReading.Ready(AppliedRate(clean(rate), RateBasis(RateMode.REFERENCE_SPREAD, reference.rate, bps)))
    }

    private fun manual(
        settings: PricingSettings,
        reference: ReferenceRate?,
        direction: Direction,
        now: Instant,
    ): RateReading {
        val rate =
            (if (direction == Direction.ON_RAMP) settings.manualBuyRate else settings.manualSellRate)
                ?: return RateReading.Paused(RatePause.MANUAL_MISSING)
        val expires = settings.manualExpiresAt ?: return RateReading.Paused(RatePause.MANUAL_MISSING)
        if (!now.isBefore(expires)) return RateReading.Paused(RatePause.MANUAL_EXPIRED)
        val known = reference?.rate?.takeIf { it.signum() > 0 }
        val margin = known?.let { marginBps(rate, it, direction) }
        return RateReading.Ready(AppliedRate(clean(rate), RateBasis(RateMode.MANUAL, known, margin)))
    }

    /**
     * What [rate] earns over [reference] in basis points, the way a spread is counted: above the reference when buying,
     * below it when selling (negative when the rate is on the user's side of the reference).
     */
    fun marginBps(
        rate: BigDecimal,
        reference: BigDecimal,
        direction: Direction,
    ): Int {
        val over = rate.subtract(reference).multiply(BPS).divide(reference, 0, RoundingMode.HALF_UP)
        val signed = if (direction == Direction.ON_RAMP) over else over.negate()
        return signed.max(BigDecimal(Int.MIN_VALUE)).min(BigDecimal(Int.MAX_VALUE)).toInt()
    }

    /** 130.000000 is 130 and 129.250000 is 129.25, as the reference rate itself is written. */
    private fun clean(rate: BigDecimal): BigDecimal {
        val stripped = rate.stripTrailingZeros()
        return if (stripped.scale() < 0) stripped.setScale(0) else stripped
    }
}
