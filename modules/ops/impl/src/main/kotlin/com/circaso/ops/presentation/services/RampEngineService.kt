package com.circaso.ops.presentation.services

import com.circaso.ops.domain.models.Actor
import com.circaso.ops.domain.models.RampEngineSettingsUpdate
import com.circaso.ops.domain.models.RampMerchantTierUpdate
import com.circaso.ops.domain.usecases.GetRampEngineSettings
import com.circaso.ops.domain.usecases.GetRampMerchantSignals
import com.circaso.ops.domain.usecases.GetRampRoutingDecision
import com.circaso.ops.domain.usecases.GetRampRoutingDecisions
import com.circaso.ops.domain.usecases.SetRampMerchantTier
import com.circaso.ops.domain.usecases.SimulateRampRouting
import com.circaso.ops.domain.usecases.UpdateRampEngineSettings
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.OpsEngineSettingsChange
import com.circaso.ramp.api.OpsEngineSettingsVersion
import com.circaso.ramp.api.OpsMerchant
import com.circaso.ramp.api.OpsMerchantSignals
import com.circaso.ramp.api.OpsRoutingCursor
import com.circaso.ramp.api.OpsRoutingDecision
import com.circaso.ramp.api.OpsRoutingQuery
import com.circaso.ramp.api.OpsSimulation
import com.circaso.ramp.api.OpsSimulationCommand
import com.circaso.shared.Network
import com.circaso.shared.Resource
import java.util.UUID

/** The merchant engine for staff (decision 2026-11-23): its settings, the routing log, a simulation, merchant tiers. */
class RampEngineService(
    private val getRampEngineSettings: GetRampEngineSettings,
    private val updateRampEngineSettings: UpdateRampEngineSettings,
    private val getRampRoutingDecisions: GetRampRoutingDecisions,
    private val getRampRoutingDecision: GetRampRoutingDecision,
    private val simulateRampRouting: SimulateRampRouting,
    private val setRampMerchantTier: SetRampMerchantTier,
    private val getRampMerchantSignals: GetRampMerchantSignals,
) {
    suspend fun settings(network: Network, direction: String): Resource<OpsEngineSettingsVersion> =
        getRampEngineSettings(network, direction)

    suspend fun updateSettings(actor: Actor, update: RampEngineSettingsUpdate): Resource<OpsEngineSettingsChange> =
        updateRampEngineSettings(actor, update)

    suspend fun decisions(
        query: OpsRoutingQuery,
        after: OpsRoutingCursor?,
        limit: Int,
    ): Resource<List<OpsRoutingDecision>> = getRampRoutingDecisions(query, after, limit)

    suspend fun decision(decisionId: UUID): Resource<OpsRoutingDecision> = getRampRoutingDecision(decisionId)

    suspend fun simulate(request: OpsSimulationCommand): Resource<OpsSimulation> = simulateRampRouting(request)

    suspend fun setMerchantTier(actor: Actor, update: RampMerchantTierUpdate): Resource<OpsMerchant> =
        setRampMerchantTier(actor, update)

    suspend fun merchantSignals(merchantId: MerchantId, network: Network): Resource<OpsMerchantSignals> =
        getRampMerchantSignals(merchantId, network)
}
