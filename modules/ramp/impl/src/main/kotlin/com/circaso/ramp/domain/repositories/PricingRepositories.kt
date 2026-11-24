package com.circaso.ramp.domain.repositories

import com.circaso.ramp.domain.models.PricingSettings
import com.circaso.shared.Network

/** `ramp.pricing_settings`: one row per network, staff-edited, version-checked. */
interface PricingSettingsRepository {
    /** The row of every network that has one. */
    suspend fun load(): Map<Network, PricingSettings>

    /** Writes a network's first row; does nothing when it already has one. */
    suspend fun seed(settings: PricingSettings)

    /**
     * Replaces a network's settings with the next version: with [expectedVersion], only when the row is still at it.
     * The row afterwards, or null when someone changed it meanwhile.
     */
    suspend fun update(settings: PricingSettings, expectedVersion: Long?): PricingSettings?
}
