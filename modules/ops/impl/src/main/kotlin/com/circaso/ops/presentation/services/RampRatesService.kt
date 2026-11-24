package com.circaso.ops.presentation.services

import com.circaso.ops.domain.models.Actor
import com.circaso.ops.domain.models.RampPricingUpdate
import com.circaso.ops.domain.usecases.GetRampPricing
import com.circaso.ops.domain.usecases.GetRampRates
import com.circaso.ops.domain.usecases.UpdateRampPricing
import com.circaso.ramp.api.OpsPricingChange
import com.circaso.ramp.api.OpsPricingSettings
import com.circaso.ramp.api.OpsRates
import com.circaso.shared.Network
import com.circaso.shared.Resource

/** The ramp's rates for staff (decision 2026-11-24): the pricing settings per network, and the rates in force. */
class RampRatesService(
    private val getRampPricing: GetRampPricing,
    private val updateRampPricing: UpdateRampPricing,
    private val getRampRates: GetRampRates,
) {
    suspend fun pricing(network: Network): Resource<OpsPricingSettings> = getRampPricing(network)

    suspend fun updatePricing(actor: Actor, update: RampPricingUpdate): Resource<OpsPricingChange> =
        updateRampPricing(actor, update)

    suspend fun rates(network: Network): Resource<OpsRates> = getRampRates(network)
}
