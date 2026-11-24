package com.circaso.ramp.presentation.services

import com.circaso.ramp.domain.models.PricingChange
import com.circaso.ramp.domain.models.PricingSettings
import com.circaso.ramp.domain.models.RatesView
import com.circaso.ramp.domain.usecases.GetPricingSettings
import com.circaso.ramp.domain.usecases.GetRates
import com.circaso.ramp.domain.usecases.UpdatePricingSettings
import com.circaso.shared.Network
import com.circaso.shared.Resource

/** What staff ask about the ramp's rates, behind `ramp:api`'s `RampRatesAdmin`. Each method is one use case. */
class RampRatesService(
    private val getPricingSettings: GetPricingSettings,
    private val updatePricingSettings: UpdatePricingSettings,
    private val getRates: GetRates,
) {
    suspend fun pricing(network: Network): Resource<PricingSettings> = getPricingSettings(network)

    suspend fun updatePricing(
        settings: PricingSettings,
        expectedVersion: Long?,
        by: String,
        reason: String,
    ): Resource<PricingChange> = updatePricingSettings(settings, expectedVersion, by, reason)

    suspend fun rates(network: Network): Resource<RatesView> = getRates(network)
}
