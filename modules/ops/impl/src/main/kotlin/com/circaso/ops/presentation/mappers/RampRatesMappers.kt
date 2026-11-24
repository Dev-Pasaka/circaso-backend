package com.circaso.ops.presentation.mappers

import com.circaso.ops.domain.models.RampPricingUpdate
import com.circaso.ops.presentation.dto.RampPricingResponse
import com.circaso.ops.presentation.dto.RampRatesResponse
import com.circaso.ops.presentation.dto.RampSideRateDto
import com.circaso.ops.presentation.dto.UpdateRampPricingRequest
import com.circaso.ramp.api.OpsPricingSettings
import com.circaso.ramp.api.OpsRates
import com.circaso.ramp.api.OpsSideRate
import com.circaso.ramp.api.RampRateModes
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.map

fun UpdateRampPricingRequest.toUpdate(network: Network): Resource<RampPricingUpdate> {
    val wanted =
        RampRateModes.ALL.firstOrNull { it == mode.trim().uppercase() }
            ?: return malformed("mode", "must be one of ${RampRateModes.ALL}")
    return rampInstant(manualExpiresAt?.takeIf { it.isNotBlank() }, "manualExpiresAt").map { expires ->
        RampPricingUpdate(
            settings =
                OpsPricingSettings(
                    network = network,
                    mode = wanted,
                    buySpreadBps = buySpreadBps,
                    sellSpreadBps = sellSpreadBps,
                    manualBuyRate = manualBuyRate,
                    manualSellRate = manualSellRate,
                    manualExpiresAt = expires,
                    maxDeviationBps = maxDeviationBps,
                    maxReferenceAgeHours = maxReferenceAgeHours,
                ),
            version = version,
            reason = reason,
        )
    }
}

fun OpsPricingSettings.toResponse() =
    RampPricingResponse(
        network = network.name,
        mode = mode,
        buySpreadBps = buySpreadBps,
        sellSpreadBps = sellSpreadBps,
        manualBuyRate = manualBuyRate,
        manualSellRate = manualSellRate,
        manualExpiresAt = manualExpiresAt?.toString(),
        maxDeviationBps = maxDeviationBps,
        maxReferenceAgeHours = maxReferenceAgeHours,
        version = version,
        updatedBy = updatedBy,
        reason = reason,
        updatedAt = updatedAt.toString(),
    )

fun OpsRates.toResponse() =
    RampRatesResponse(
        network = network.name,
        mode = mode,
        referenceRate = referenceRate,
        referenceAsOf = referenceAsOf?.toString(),
        referenceAgeSeconds = referenceAgeSeconds,
        referenceStale = referenceStale,
        maxReferenceAgeHours = maxReferenceAgeHours,
        maxDeviationBps = maxDeviationBps,
        manualExpiresAt = manualExpiresAt?.toString(),
        buy = buy.toDto(),
        sell = sell.toDto(),
        at = at.toString(),
    )

private fun OpsSideRate.toDto() = RampSideRateDto(rate, source, spreadBps, paused)
