package com.circaso.ramp.presentation.services

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.EngineSettingsChange
import com.circaso.ramp.domain.models.EngineSettingsVersion
import com.circaso.ramp.domain.models.MerchantPerformance
import com.circaso.ramp.domain.models.MerchantRecord
import com.circaso.ramp.domain.models.MerchantType
import com.circaso.ramp.domain.models.PageCursor
import com.circaso.ramp.domain.models.RoutingDecision
import com.circaso.ramp.domain.models.RoutingQuery
import com.circaso.ramp.domain.models.Simulation
import com.circaso.ramp.domain.models.SimulationCommand
import com.circaso.ramp.domain.usecases.GetEngineSettings
import com.circaso.ramp.domain.usecases.GetMerchantPerformance
import com.circaso.ramp.domain.usecases.GetRoutingDecision
import com.circaso.ramp.domain.usecases.ListRoutingDecisions
import com.circaso.ramp.domain.usecases.PruneRoutingLog
import com.circaso.ramp.domain.usecases.SetMerchantTier
import com.circaso.ramp.domain.usecases.SimulateRouting
import com.circaso.ramp.domain.usecases.UpdateEngineSettings
import com.circaso.shared.Network
import com.circaso.shared.Resource
import java.util.UUID

/**
 * What staff ask the merchant engine, behind `ramp:api`'s `RampEngineAdmin`, and the engine's own job. No logic of
 * its own: each method is one use case.
 */
class RampEngineService(
    private val getEngineSettings: GetEngineSettings,
    private val updateEngineSettings: UpdateEngineSettings,
    private val listRoutingDecisions: ListRoutingDecisions,
    private val getRoutingDecision: GetRoutingDecision,
    private val simulateRouting: SimulateRouting,
    private val setMerchantTierUseCase: SetMerchantTier,
    private val getMerchantPerformance: GetMerchantPerformance,
    private val pruneRoutingLog: PruneRoutingLog,
) {
    suspend fun settings(network: Network, direction: Direction): Resource<EngineSettingsVersion> =
        getEngineSettings(network, direction)

    suspend fun updateSettings(
        change: EngineSettingsVersion,
        expectedVersion: Long?,
        by: String,
        reason: String,
    ): Resource<EngineSettingsChange> = updateEngineSettings(change, expectedVersion, by, reason)

    suspend fun decisions(
        query: RoutingQuery,
        after: PageCursor?,
        limit: Int,
    ): Resource<List<RoutingDecision>> = listRoutingDecisions(query, after, limit)

    suspend fun decision(decisionId: UUID): Resource<RoutingDecision> = getRoutingDecision(decisionId)

    suspend fun simulate(command: SimulationCommand): Resource<Simulation> = simulateRouting(command)

    suspend fun setMerchantTier(
        merchantId: MerchantId,
        type: MerchantType,
        orderLimitMinor: Long?,
    ): Resource<MerchantRecord> = setMerchantTierUseCase(merchantId, type, orderLimitMinor)

    suspend fun merchantPerformance(merchantId: MerchantId, network: Network): Resource<MerchantPerformance> =
        getMerchantPerformance(merchantId, network)

    /** `ramp.prune-routing`. */
    suspend fun pruneRoutingLog(): Int = pruneRoutingLog.invoke()
}
