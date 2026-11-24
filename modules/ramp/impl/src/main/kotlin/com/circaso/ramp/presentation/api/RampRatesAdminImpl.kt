package com.circaso.ramp.presentation.api

import com.circaso.ramp.api.OpsPricingChange
import com.circaso.ramp.api.OpsPricingSettings
import com.circaso.ramp.api.OpsRates
import com.circaso.ramp.api.OpsSideRate
import com.circaso.ramp.api.RampRateModes
import com.circaso.ramp.api.RampRatesAdmin
import com.circaso.ramp.domain.models.PricingSettings
import com.circaso.ramp.domain.models.RateMode
import com.circaso.ramp.domain.models.RateReading
import com.circaso.ramp.domain.models.RatesView
import com.circaso.ramp.presentation.services.RampRatesService
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.flatMap
import com.circaso.shared.map
import java.math.BigDecimal
import java.time.Duration
import java.util.UUID

/** ramp:api's staff contract for the ramp's rates. Every call goes through the service, like any other way in. */
class RampRatesAdminImpl(private val rates: RampRatesService) : RampRatesAdmin {
    override suspend fun pricing(network: Network): Resource<OpsPricingSettings> =
        rates.pricing(network).map { it.toOps() }

    override suspend fun updatePricing(
        settings: OpsPricingSettings,
        version: Long?,
        reason: String,
        by: UUID,
    ): Resource<OpsPricingChange> =
        settings.toDomain().flatMap { wanted ->
            rates
                .updatePricing(wanted, version, by.toString(), reason)
                .map { OpsPricingChange(it.before.toOps(), it.after.toOps()) }
        }

    override suspend fun rates(network: Network): Resource<OpsRates> = rates.rates(network).map { it.toOps() }
}

private fun OpsPricingSettings.toDomain(): Resource<PricingSettings> {
    val wanted =
        RateMode.entries.firstOrNull { it.name == mode.trim().uppercase() }
            ?: return Resource.error(SharedError.Malformed("mode", "must be one of ${RampRateModes.ALL}"))
    val buy = rateOf(manualBuyRate, "manualBuyRate")
    val sell = rateOf(manualSellRate, "manualSellRate")
    if (buy is Resource.Error) return buy
    if (sell is Resource.Error) return sell
    return Resource.success(
        PricingSettings(
            network = network,
            mode = wanted,
            buySpreadBps = buySpreadBps,
            sellSpreadBps = sellSpreadBps,
            manualBuyRate = (buy as Resource.Success).data,
            manualSellRate = (sell as Resource.Success).data,
            manualExpiresAt = manualExpiresAt,
            maxDeviationBps = maxDeviationBps,
            maxReferenceAgeHours = maxReferenceAgeHours,
        ),
    )
}

private fun rateOf(raw: String?, field: String): Resource<BigDecimal?> {
    val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return Resource.success(null)
    return text.toBigDecimalOrNull()?.let { Resource.success(it) }
        ?: Resource.error(SharedError.Malformed(field, "must be a decimal number of shillings, like 129.25"))
}

private fun PricingSettings.toOps() =
    OpsPricingSettings(
        network = network,
        mode = mode.name,
        buySpreadBps = buySpreadBps,
        sellSpreadBps = sellSpreadBps,
        manualBuyRate = manualBuyRate?.toPlainString(),
        manualSellRate = manualSellRate?.toPlainString(),
        manualExpiresAt = manualExpiresAt,
        maxDeviationBps = maxDeviationBps,
        maxReferenceAgeHours = maxReferenceAgeHours,
        version = version,
        updatedBy = updatedBy,
        reason = reason,
        updatedAt = updatedAt,
    )

private fun RatesView.toOps() =
    OpsRates(
        network = settings.network,
        mode = settings.mode.name,
        referenceRate = reference?.rate?.toPlainString(),
        referenceAsOf = reference?.asOf,
        referenceAgeSeconds = reference?.let { Duration.between(it.asOf, at).seconds.coerceAtLeast(0) },
        referenceStale = referenceStale,
        maxReferenceAgeHours = settings.maxReferenceAgeHours,
        maxDeviationBps = settings.maxDeviationBps,
        manualExpiresAt = settings.manualExpiresAt,
        buy = buy.toOps(settings.mode),
        sell = sell.toOps(settings.mode),
        at = at,
    )

private fun RateReading.toOps(mode: RateMode): OpsSideRate =
    when (this) {
        is RateReading.Ready -> {
            OpsSideRate(applied.rate.toPlainString(), applied.basis.source.name, applied.basis.spreadBps, null)
        }

        is RateReading.Paused -> {
            OpsSideRate(null, mode.name, null, why.name)
        }
    }
