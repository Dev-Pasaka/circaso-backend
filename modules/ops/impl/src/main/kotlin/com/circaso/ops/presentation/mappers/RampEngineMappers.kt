package com.circaso.ops.presentation.mappers

import com.circaso.ops.domain.models.RampEngineSettingsUpdate
import com.circaso.ops.domain.models.RampMerchantTierUpdate
import com.circaso.ops.presentation.dto.RampEngineSettingsDto
import com.circaso.ops.presentation.dto.RampEngineSettingsResponse
import com.circaso.ops.presentation.dto.RampMerchantPerformanceDto
import com.circaso.ops.presentation.dto.RampMerchantSignalsResponse
import com.circaso.ops.presentation.dto.RampMerchantStatsDto
import com.circaso.ops.presentation.dto.RampMerchantTierRequest
import com.circaso.ops.presentation.dto.RampRoutingCandidateDto
import com.circaso.ops.presentation.dto.RampRoutingDecisionResponse
import com.circaso.ops.presentation.dto.RampRoutingListResponse
import com.circaso.ops.presentation.dto.RampRoutingScoreDto
import com.circaso.ops.presentation.dto.RampRoutingStepDto
import com.circaso.ops.presentation.dto.RampRoutingSummaryDto
import com.circaso.ops.presentation.dto.RampSimulatedShareDto
import com.circaso.ops.presentation.dto.RampSimulationRequest
import com.circaso.ops.presentation.dto.RampSimulationResponse
import com.circaso.ops.presentation.dto.UpdateRampEngineSettingsRequest
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.OpsEngineSettings
import com.circaso.ramp.api.OpsEngineSettingsVersion
import com.circaso.ramp.api.OpsMerchantPerformance
import com.circaso.ramp.api.OpsMerchantSignals
import com.circaso.ramp.api.OpsMerchantStats
import com.circaso.ramp.api.OpsRoutingCandidate
import com.circaso.ramp.api.OpsRoutingCursor
import com.circaso.ramp.api.OpsRoutingDecision
import com.circaso.ramp.api.OpsSimulation
import com.circaso.ramp.api.OpsSimulationCommand
import com.circaso.ramp.api.RampDirections
import com.circaso.ramp.api.RampOrderId
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.flatMap
import com.circaso.shared.map
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

// Shape checks and answers for the merchant engine's staff routes. A problem names the field as the request spells it.

private const val CURSOR_SEPARATOR = "~"
private const val MAX_SIMULATED_ORDERS = 500

/** `?direction=`, which the engine's settings routes need: one of ON_RAMP, OFF_RAMP (any case). */
fun rampDirection(raw: String?): Resource<String> {
    val direction = raw?.trim()?.uppercase()
    return if (direction in RampDirections.ALL && direction != null) {
        Resource.success(direction)
    } else {
        malformed("direction", "is required: ON_RAMP or OFF_RAMP")
    }
}

/** KES cents as a string of digits. */
private fun minor(raw: String, field: String): Resource<Long> =
    raw
        .trim()
        .takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        ?.toLongOrNull()
        ?.let { Resource.success(it) }
        ?: malformed(field, "must be KES cents as digits")

private fun RampEngineSettingsDto.toSettings(): Resource<OpsEngineSettings> =
    minor(smallOrderMinor, "settings.smallOrderMinor").flatMap { small ->
        minor(individualLimitMinor, "settings.individualLimitMinor").flatMap { individual ->
            minor(businessLimitMinor, "settings.businessLimitMinor").map { business ->
                OpsEngineSettings(
                    mode = mode.trim().uppercase(),
                    weightCompletion = weightCompletion,
                    weightSpeed = weightSpeed,
                    weightFailure = weightFailure,
                    priorStrength = priorStrength,
                    completionPrior = completionPrior,
                    failurePrior = failurePrior,
                    speedPrior = speedPrior,
                    qualityFloor = qualityFloor,
                    alpha = alpha,
                    hungerMax = hungerMax,
                    hungerWindowMinutes = hungerWindowMinutes,
                    newcomerMax = newcomerMax,
                    newcomerTrades = newcomerTrades,
                    smallOrderMinor = small,
                    floatCover = floatCover,
                    shareThreshold = shareThreshold,
                    sharePenalty = sharePenalty,
                    slaSeconds = slaSeconds,
                    targetSeconds = targetSeconds,
                    statsOrders = statsOrders,
                    statsDays = statsDays,
                    maxAttempts = maxAttempts,
                    individualLimitMinor = individual,
                    businessLimitMinor = business,
                    logRetentionDays = logRetentionDays,
                )
            }
        }
    }

fun UpdateRampEngineSettingsRequest.toUpdate(network: Network, direction: String): Resource<RampEngineSettingsUpdate> =
    settings.toSettings().map { RampEngineSettingsUpdate(network, direction, it, version, reason) }

fun RampSimulationRequest.toCommand(network: Network, direction: String): Resource<OpsSimulationCommand> {
    if (lastOrders != null && amountMinor != null) return malformed("amountMinor", "send lastOrders or amountMinor")
    if (lastOrders != null && lastOrders !in 1..MAX_SIMULATED_ORDERS) return malformed("lastOrders", "must be 1 to 500")
    val amount =
        when (val parsed = amountMinor?.let { minor(it, "amountMinor") }) {
            null -> {
                null
            }

            is Resource.Error -> {
                return parsed
            }

            is Resource.Success -> {
                parsed.data.takeIf { it > 0 }
                    ?: return malformed("amountMinor", "must be above zero")
            }
        }
    val draft =
        when (val parsed = settings?.toSettings()) {
            null -> null
            is Resource.Error -> return parsed
            is Resource.Success -> parsed.data
        }
    return Resource.success(OpsSimulationCommand(network, direction, draft, lastOrders, amount))
}

fun RampMerchantTierRequest.toUpdate(merchantId: MerchantId): Resource<RampMerchantTierUpdate> {
    val limit =
        when (val parsed = orderLimitMinor?.let { minor(it, "orderLimitMinor") }) {
            null -> null
            is Resource.Error -> return parsed
            is Resource.Success -> parsed.data
        }
    return Resource.success(RampMerchantTierUpdate(merchantId, merchantType.trim().uppercase(), limit, reason))
}

/** An optional `?orderId=`. */
fun rampOrderId(raw: String?): Resource<RampOrderId?> =
    when {
        raw == null -> Resource.success(null)
        else -> RampOrderId.parse(raw)?.let { Resource.success(it) } ?: malformed("orderId", "must be an order id")
    }

/** An optional `?merchantId=`. */
fun optionalMerchantId(raw: String?): Resource<MerchantId?> =
    if (raw == null) Resource.success(null) else parseMerchantId(raw)

/** An optional instant (`2026-11-23T09:00:00Z`) in `?from=` or `?to=`. */
fun rampInstant(raw: String?, field: String): Resource<Instant?> {
    if (raw == null) return Resource.success(null)
    return try {
        Resource.success(Instant.parse(raw))
    } catch (_: DateTimeParseException) {
        malformed(field, "must be a time like 2026-11-23T09:00:00Z")
    }
}

fun routingCursor(raw: String?): Resource<OpsRoutingCursor?> {
    if (raw == null) return Resource.success(null)
    val parts = raw.split(CURSOR_SEPARATOR)
    val cursor =
        runCatching { OpsRoutingCursor(Instant.parse(parts[0]), UUID.fromString(parts[1])) }
            .getOrNull()
            ?.takeIf { parts.size == 2 }
    return cursor?.let { Resource.success(it) } ?: malformed("cursor", "must be a nextCursor of this list")
}

private fun OpsEngineSettings.toDto() =
    RampEngineSettingsDto(
        mode = mode,
        weightCompletion = weightCompletion,
        weightSpeed = weightSpeed,
        weightFailure = weightFailure,
        priorStrength = priorStrength,
        completionPrior = completionPrior,
        failurePrior = failurePrior,
        speedPrior = speedPrior,
        qualityFloor = qualityFloor,
        alpha = alpha,
        hungerMax = hungerMax,
        hungerWindowMinutes = hungerWindowMinutes,
        newcomerMax = newcomerMax,
        newcomerTrades = newcomerTrades,
        smallOrderMinor = smallOrderMinor.toString(),
        floatCover = floatCover,
        shareThreshold = shareThreshold,
        sharePenalty = sharePenalty,
        slaSeconds = slaSeconds,
        targetSeconds = targetSeconds,
        statsOrders = statsOrders,
        statsDays = statsDays,
        maxAttempts = maxAttempts,
        individualLimitMinor = individualLimitMinor.toString(),
        businessLimitMinor = businessLimitMinor.toString(),
        logRetentionDays = logRetentionDays,
    )

fun OpsEngineSettingsVersion.toResponse() =
    RampEngineSettingsResponse(
        network = network.name,
        direction = direction,
        version = version,
        settings = settings.toDto(),
        updatedBy = updatedBy,
        reason = reason,
        updatedAt = updatedAt.toString(),
    )

private fun OpsMerchantStats.toDto() =
    RampMerchantStatsDto(
        finished = finished,
        completed = completed,
        merchantFailures = merchantFailures,
        merchantExpiries = merchantExpiries,
        reversals = reversals,
        counted = counted,
        notCounted = notCounted,
        medianHandlingSeconds = medianHandlingSeconds,
        p90HandlingSeconds = p90HandlingSeconds,
    )

private fun OpsRoutingCandidate.toDto() =
    RampRoutingCandidateDto(
        merchantId = merchantId.toString(),
        excluded = excluded,
        approved = approved,
        kycCleared = kycCleared,
        online = online,
        hasKeys = hasKeys,
        paymentsIssue = paymentsIssue,
        offersDirection = offersDirection,
        limitMinor = limitMinor.toString(),
        floatMinor = floatMinor?.toString(),
        lastAssignedAt = lastAssignedAt?.toString(),
        completedTrades = completedTrades,
        stats = stats?.toDto(),
        score =
            score?.let {
                RampRoutingScoreDto(
                    completion = it.completion,
                    speed = it.speed,
                    failure = it.failure,
                    quality = it.quality,
                    hunger = it.hunger,
                    newcomer = it.newcomer,
                    sizeFit = it.sizeFit,
                    weight = it.weight,
                )
            },
    )

fun OpsRoutingDecision.toResponse() =
    RampRoutingDecisionResponse(
        decisionId = decisionId.toString(),
        createdAt = createdAt.toString(),
        network = network.name,
        direction = direction,
        quoteId = quoteId.toString(),
        orderId = orderId?.toString(),
        merchantId = merchantId?.toString(),
        fiatAmountMinor = fiatAmountMinor.toString(),
        needMinor = needMinor.toString(),
        mode = mode,
        outcome = outcome,
        settingsVersion = settingsVersion,
        candidates = candidates.map { it.toDto() },
        ranked = ranked.map { it.toString() },
        engineRanked = engineRanked?.map { it.toString() },
        steps =
            steps.map {
                RampRoutingStepDto(
                    it.merchantId.toString(),
                    it.orderId?.toString(),
                    it.result,
                    it.at.toString(),
                )
            },
    )

private fun OpsRoutingDecision.toSummary() =
    RampRoutingSummaryDto(
        decisionId = decisionId.toString(),
        createdAt = createdAt.toString(),
        quoteId = quoteId.toString(),
        orderId = orderId?.toString(),
        merchantId = merchantId?.toString(),
        direction = direction,
        fiatAmountMinor = fiatAmountMinor.toString(),
        mode = mode,
        outcome = outcome,
        settingsVersion = settingsVersion,
        candidates = candidates.size,
        eligible = candidates.count { it.excluded == null },
        attempts = steps.count { it.orderId != null },
    )

fun List<OpsRoutingDecision>.toRoutingResponse(limit: Int) =
    RampRoutingListResponse(
        decisions = map { it.toSummary() },
        nextCursor = lastOrNull()?.takeIf { size >= limit }?.let { "${it.createdAt}$CURSOR_SEPARATOR${it.decisionId}" },
    )

/** A share (0 to 1) as a percentage with at most one decimal. */
private fun percent(share: Double): String =
    BigDecimal(share * PERCENT).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

private const val PERCENT = 100

fun OpsSimulation.toResponse() =
    RampSimulationResponse(
        network = network.name,
        direction = direction,
        mode = mode,
        currentMode = currentMode,
        orders = orders,
        unroutable = unroutable,
        shares =
            shares.map {
                RampSimulatedShareDto(
                    merchantId = it.merchantId.toString(),
                    displayName = it.displayName,
                    sharePercent = percent(it.share),
                    currentSharePercent = percent(it.currentShare),
                    eligibleOrders = it.eligibleOrders,
                    exclusions = it.exclusions,
                    quality = it.quality,
                )
            },
    )

private fun OpsMerchantPerformance.toDto() =
    RampMerchantPerformanceDto(
        direction = direction,
        offered = offered,
        orderLimitMinor = orderLimitMinor.toString(),
        stats = stats.toDto(),
        trades = trades,
        newcomerTrades = newcomerTrades,
        newcomerTradesLeft = newcomerTradesLeft,
        targetSeconds = targetSeconds,
        slaSeconds = slaSeconds,
        statsOrders = statsOrders,
        statsDays = statsDays,
    )

fun OpsMerchantSignals.toResponse() =
    RampMerchantSignalsResponse(
        merchantId = merchantId.toString(),
        network = network.name,
        merchantType = merchantType,
        directions = directions.map { it.toDto() },
    )
