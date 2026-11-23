package com.circaso.ramp.presentation.api

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.OpsEngineSettings
import com.circaso.ramp.api.OpsEngineSettingsChange
import com.circaso.ramp.api.OpsEngineSettingsVersion
import com.circaso.ramp.api.OpsMerchant
import com.circaso.ramp.api.OpsMerchantPerformance
import com.circaso.ramp.api.OpsMerchantSignals
import com.circaso.ramp.api.OpsMerchantStats
import com.circaso.ramp.api.OpsRoutingCandidate
import com.circaso.ramp.api.OpsRoutingCursor
import com.circaso.ramp.api.OpsRoutingDecision
import com.circaso.ramp.api.OpsRoutingQuery
import com.circaso.ramp.api.OpsRoutingScore
import com.circaso.ramp.api.OpsRoutingStep
import com.circaso.ramp.api.OpsSimulatedShare
import com.circaso.ramp.api.OpsSimulation
import com.circaso.ramp.api.OpsSimulationCommand
import com.circaso.ramp.api.RampDirections
import com.circaso.ramp.api.RampEngineAdmin
import com.circaso.ramp.api.RampEngineModes
import com.circaso.ramp.api.RampMerchantTypes
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.EngineMode
import com.circaso.ramp.domain.models.EngineSettings
import com.circaso.ramp.domain.models.EngineSettingsVersion
import com.circaso.ramp.domain.models.MerchantPerformance
import com.circaso.ramp.domain.models.MerchantStats
import com.circaso.ramp.domain.models.MerchantType
import com.circaso.ramp.domain.models.PageCursor
import com.circaso.ramp.domain.models.Performance
import com.circaso.ramp.domain.models.RoutedCandidate
import com.circaso.ramp.domain.models.RoutingDecision
import com.circaso.ramp.domain.models.RoutingQuery
import com.circaso.ramp.domain.models.Simulation
import com.circaso.ramp.domain.models.SimulationCommand
import com.circaso.ramp.presentation.services.RampEngineService
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.flatMap
import com.circaso.shared.map
import java.time.Instant
import java.util.UUID

/** ramp:api's staff contract for the merchant engine. Every call goes through the service, like any other way in. */
class RampEngineAdminImpl(private val engine: RampEngineService) : RampEngineAdmin {
    override suspend fun settings(network: Network, direction: String): Resource<OpsEngineSettingsVersion> =
        directionOf(direction).flatMap { engine.settings(network, it) }.map { it.toOps() }

    override suspend fun updateSettings(
        network: Network,
        direction: String,
        settings: OpsEngineSettings,
        version: Long?,
        reason: String,
        by: UUID,
    ): Resource<OpsEngineSettingsChange> =
        directionOf(direction).flatMap { wanted ->
            settings.toDomain().flatMap { document ->
                val change = EngineSettingsVersion(network, wanted, version ?: 0, document, null, null, Instant.EPOCH)
                engine
                    .updateSettings(change, version, by.toString(), reason)
                    .map { OpsEngineSettingsChange(it.before.toOps(), it.after.toOps()) }
            }
        }

    override suspend fun decisions(
        query: OpsRoutingQuery,
        after: OpsRoutingCursor?,
        limit: Int,
    ): Resource<List<OpsRoutingDecision>> {
        val asked = RoutingQuery(query.network, query.merchantId, query.orderId, query.from, query.to)
        val cursor = after?.let { PageCursor(it.at, it.decisionId) }
        return engine.decisions(asked, cursor, limit).map { page -> page.map { it.toOps() } }
    }

    override suspend fun decision(decisionId: UUID): Resource<OpsRoutingDecision> =
        engine.decision(decisionId).map { it.toOps() }

    override suspend fun simulate(request: OpsSimulationCommand): Resource<OpsSimulation> =
        directionOf(request.direction).flatMap { direction ->
            val draft =
                when (val given = request.draft?.toDomain()) {
                    null -> null
                    is Resource.Error -> return@flatMap given
                    is Resource.Success -> given.data
                }
            val command = SimulationCommand(request.network, direction, draft, request.lastOrders, request.fiatMinor)
            engine.simulate(command).map { it.toOps() }
        }

    override suspend fun setMerchantTier(
        merchantId: MerchantId,
        merchantType: String,
        orderLimitMinor: Long?,
        by: UUID,
    ): Resource<OpsMerchant> {
        val type =
            MerchantType.entries.firstOrNull { it.name == merchantType.trim().uppercase() }
                ?: return malformed("merchantType", RampMerchantTypes.ALL)
        return engine.setMerchantTier(merchantId, type, orderLimitMinor).map { it.toOps() }
    }

    override suspend fun merchantSignals(merchantId: MerchantId, network: Network): Resource<OpsMerchantSignals> =
        engine.merchantPerformance(merchantId, network).map { it.toOps() }

    private fun directionOf(name: String): Resource<Direction> =
        Direction.entries.firstOrNull { it.name == name.trim().uppercase() }?.let { Resource.success(it) }
            ?: malformed("direction", RampDirections.ALL)

    private fun malformed(field: String, allowed: List<String>): Resource<Nothing> =
        Resource.error(SharedError.Malformed(field, "must be one of $allowed"))
}

private fun OpsEngineSettings.toDomain(): Resource<EngineSettings> {
    val wanted =
        EngineMode.entries.firstOrNull { it.name == mode.trim().uppercase() }
            ?: return Resource.error(SharedError.Malformed("mode", "must be one of ${RampEngineModes.ALL}"))
    return Resource.success(
        EngineSettings(
            mode = wanted,
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
            smallOrderMinor = smallOrderMinor,
            floatCover = floatCover,
            shareThreshold = shareThreshold,
            sharePenalty = sharePenalty,
            slaSeconds = slaSeconds,
            targetSeconds = targetSeconds,
            statsOrders = statsOrders,
            statsDays = statsDays,
            maxAttempts = maxAttempts,
            individualLimitMinor = individualLimitMinor,
            businessLimitMinor = businessLimitMinor,
            logRetentionDays = logRetentionDays,
        ),
    )
}

private fun EngineSettings.toOps() =
    OpsEngineSettings(
        mode = mode.name,
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
        smallOrderMinor = smallOrderMinor,
        floatCover = floatCover,
        shareThreshold = shareThreshold,
        sharePenalty = sharePenalty,
        slaSeconds = slaSeconds,
        targetSeconds = targetSeconds,
        statsOrders = statsOrders,
        statsDays = statsDays,
        maxAttempts = maxAttempts,
        individualLimitMinor = individualLimitMinor,
        businessLimitMinor = businessLimitMinor,
        logRetentionDays = logRetentionDays,
    )

private fun EngineSettingsVersion.toOps() =
    OpsEngineSettingsVersion(network, direction.name, version, settings.toOps(), updatedBy, reason, updatedAt)

private fun MerchantStats.toOps() =
    OpsMerchantStats(
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

private fun RoutedCandidate.toOps() =
    OpsRoutingCandidate(
        merchantId = facts.merchantId,
        excluded = exclusion?.name,
        approved = facts.approved,
        kycCleared = facts.kycCleared,
        online = facts.online,
        hasKeys = facts.hasKeys,
        paymentsIssue = facts.paymentsIssue,
        offersDirection = facts.offersDirection,
        limitMinor = facts.limitMinor,
        floatMinor = facts.floatMinor,
        lastAssignedAt = facts.lastAssignedAt,
        completedTrades = facts.completedTrades,
        stats = facts.stats?.toOps(),
        score =
            score?.let {
                OpsRoutingScore(
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

private fun RoutingDecision.toOps() =
    OpsRoutingDecision(
        decisionId = decisionId,
        quoteId = quoteId.value,
        orderId = orderId,
        merchantId = merchantId,
        network = network,
        direction = order.direction.name,
        fiatAmountMinor = order.fiatMinor,
        needMinor = order.needMinor,
        settingsVersion = settingsVersion,
        mode = plan.mode.name,
        outcome = outcome.name,
        candidates = plan.candidates.map { it.toOps() },
        ranked = plan.ranked,
        engineRanked = plan.engineRanked,
        steps = steps.map { OpsRoutingStep(it.merchantId, it.orderId, it.result, it.at) },
        createdAt = createdAt,
    )

private fun Simulation.toOps() =
    OpsSimulation(
        network = network,
        direction = direction.name,
        mode = mode.name,
        currentMode = currentMode.name,
        orders = orders,
        unroutable = unroutable,
        shares =
            shares.map {
                OpsSimulatedShare(
                    merchantId = it.merchantId,
                    displayName = it.displayName,
                    share = it.share,
                    currentShare = it.currentShare,
                    eligibleOrders = it.eligibleOrders,
                    exclusions = it.exclusions.mapKeys { (reason, _) -> reason.name },
                    quality = it.quality,
                )
            },
    )

private fun Performance.toOps() =
    OpsMerchantPerformance(
        direction = direction.name,
        offered = offered,
        orderLimitMinor = orderLimitMinor,
        stats = stats.toOps(),
        trades = trades,
        newcomerTrades = settings.newcomerTrades,
        newcomerTradesLeft = newcomerTradesLeft,
        targetSeconds = settings.targetSeconds,
        slaSeconds = settings.slaSeconds,
        statsOrders = settings.statsOrders,
        statsDays = settings.statsDays,
    )

private fun MerchantPerformance.toOps() =
    OpsMerchantSignals(merchantId, network, merchantType.name, directions.map { it.toOps() })
