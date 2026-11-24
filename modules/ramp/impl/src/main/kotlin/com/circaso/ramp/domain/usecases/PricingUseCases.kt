package com.circaso.ramp.domain.usecases

import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.PricingChange
import com.circaso.ramp.domain.models.PricingSettings
import com.circaso.ramp.domain.models.RateMode
import com.circaso.ramp.domain.models.RatesView
import com.circaso.ramp.domain.ports.Pricing
import com.circaso.ramp.domain.repositories.PricingSettingsRepository
import com.circaso.ramp.domain.support.PricingBook
import com.circaso.ramp.domain.support.RatePolicy
import com.circaso.shared.Network
import com.circaso.shared.Resource
import java.time.Clock
import java.time.Duration

/** A network's pricing settings, read from the table now (never the ten-second copy). */
class GetPricingSettings(private val rows: PricingSettingsRepository, private val book: PricingBook) {
    suspend operator fun invoke(network: Network): Resource<PricingSettings> {
        // The book seeds a network with no row yet, so the table always answers after it.
        val inForce = book.of(network)
        return Resource.success(rows.load()[network] ?: inForce)
    }
}

/**
 * Replaces a network's pricing settings with the next version, version-checked (decision 2026-11-24). Out of range is
 * `ramp.settings_invalid` naming the field; manual rates need the buy rate at or above the sell rate, an expiry (24
 * hours when none is given) and both rates within `maxDeviationBps` of the reference rate read now, so a typo can't
 * price the ramp: with no reference to check against, manual rates can't be saved. A change made meanwhile is
 * `ramp.settings_changed`. In force everywhere within ten seconds.
 */
class UpdatePricingSettings(
    private val rows: PricingSettingsRepository,
    private val book: PricingBook,
    private val pricing: Pricing,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        wanted: PricingSettings,
        expectedVersion: Long?,
        by: String,
        reason: String,
    ): Resource<PricingChange> {
        val now = clock.instant()
        val manual = wanted.mode == RateMode.MANUAL
        val settings =
            if (manual && wanted.manualExpiresAt == null) {
                wanted.copy(manualExpiresAt = now.plus(Duration.ofHours(PricingSettings.DEFAULT_MANUAL_HOURS)))
            } else {
                wanted
            }
        settings.problem(now)?.let { (field, what) -> return Resource.error(RampError.SettingsInvalid(field, what)) }
        if (manual) {
            val reference =
                pricing.reference()
                    ?: return Resource.error(
                        RampError.SettingsInvalid("manualBuyRate", "can't be checked: no reference rate can be read"),
                    )
            settings.bandProblem(reference.rate)?.let { (field, what) ->
                return Resource.error(RampError.SettingsInvalid(field, what))
            }
        }
        book.of(settings.network)
        val before = rows.load()[settings.network] ?: return Resource.error(RampError.SettingsChanged)
        val after =
            rows.update(settings.copy(updatedBy = by, reason = reason, updatedAt = now), expectedVersion)
                ?: return Resource.error(RampError.SettingsChanged)
        book.invalidate()
        return Resource.success(PricingChange(before, after))
    }
}

/** What a network's rates are now: the reference and its age, and each side's rate in force or why it is paused. */
class GetRates(
    private val rows: PricingSettingsRepository,
    private val book: PricingBook,
    private val pricing: Pricing,
    private val clock: Clock,
) {
    suspend operator fun invoke(network: Network): Resource<RatesView> {
        val inForce = book.of(network)
        val settings = rows.load()[network] ?: inForce
        val reference = pricing.reference()
        val now = clock.instant()
        return Resource.success(
            RatesView(
                settings = settings,
                reference = reference,
                referenceStale = RatePolicy.isStale(settings, reference, now),
                buy = RatePolicy.read(settings, reference, Direction.ON_RAMP, now),
                sell = RatePolicy.read(settings, reference, Direction.OFF_RAMP, now),
                at = now,
            ),
        )
    }
}
