package com.circaso.ops.domain.usecases

import com.circaso.ops.domain.errors.OpsError
import com.circaso.ops.domain.models.Actor
import com.circaso.ops.domain.models.AuditAction
import com.circaso.ops.domain.models.RampEngineSettingsUpdate
import com.circaso.ops.domain.models.RampMerchantTierUpdate
import com.circaso.ops.domain.support.AuditTrail
import com.circaso.ops.domain.support.Reasons
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.OpsEngineSettings
import com.circaso.ramp.api.OpsEngineSettingsChange
import com.circaso.ramp.api.OpsEngineSettingsVersion
import com.circaso.ramp.api.OpsMerchant
import com.circaso.ramp.api.OpsMerchantSignals
import com.circaso.ramp.api.OpsRoutingCursor
import com.circaso.ramp.api.OpsRoutingDecision
import com.circaso.ramp.api.OpsRoutingQuery
import com.circaso.ramp.api.OpsSimulation
import com.circaso.ramp.api.OpsSimulationCommand
import com.circaso.ramp.api.RampEngineAdmin
import com.circaso.shared.Network
import com.circaso.shared.Resource
import java.util.UUID

/** The merchant engine's settings for a network and direction, as the database has them now (decision 2026-11-23). */
class GetRampEngineSettings(private val engine: RampEngineAdmin) {
    suspend operator fun invoke(network: Network, direction: String): Resource<OpsEngineSettingsVersion> =
        engine.settings(network, direction)
}

/**
 * Replaces a network and direction's engine settings with the next version. A reason is required; it is kept on the
 * version itself and in the audit log, whose line says what changed, by name.
 */
class UpdateRampEngineSettings(private val engine: RampEngineAdmin, private val trail: AuditTrail) {
    suspend operator fun invoke(actor: Actor, update: RampEngineSettingsUpdate): Resource<OpsEngineSettingsChange> {
        val why = Reasons.check(update.reason) ?: return Resource.error(OpsError.ReasonRequired)
        return trail.record<OpsEngineSettingsChange>(
            actor,
            AuditAction.RAMP_ENGINE_SETTINGS_UPDATE,
            "${update.network.name}:${update.direction}",
            reason = why,
            detail = "mode ${update.settings.mode}",
            outcome = { describe(it) },
        ) {
            engine.updateSettings(
                network = update.network,
                direction = update.direction,
                settings = update.settings,
                version = update.version,
                reason = why,
                by = actor.staffId,
            )
        }
    }

    private fun describe(change: OpsEngineSettingsChange): String {
        val parts = mutableListOf("v${change.before.version}→v${change.after.version}")
        val before = fields(change.before.settings)
        fields(change.after.settings).forEach { (name, now) ->
            val was = before[name]
            if (was != now) parts += "$name $was→$now"
        }
        return parts.joinToString("; ")
    }

    private fun fields(settings: OpsEngineSettings): Map<String, String> =
        linkedMapOf(
            "mode" to settings.mode.toString(),
            "weightCompletion" to settings.weightCompletion.toString(),
            "weightSpeed" to settings.weightSpeed.toString(),
            "weightFailure" to settings.weightFailure.toString(),
            "priorStrength" to settings.priorStrength.toString(),
            "completionPrior" to settings.completionPrior.toString(),
            "failurePrior" to settings.failurePrior.toString(),
            "speedPrior" to settings.speedPrior.toString(),
            "qualityFloor" to settings.qualityFloor.toString(),
            "alpha" to settings.alpha.toString(),
            "hungerMax" to settings.hungerMax.toString(),
            "hungerWindowMinutes" to settings.hungerWindowMinutes.toString(),
            "newcomerMax" to settings.newcomerMax.toString(),
            "newcomerTrades" to settings.newcomerTrades.toString(),
            "smallOrderMinor" to settings.smallOrderMinor.toString(),
            "floatCover" to settings.floatCover.toString(),
            "shareThreshold" to settings.shareThreshold.toString(),
            "sharePenalty" to settings.sharePenalty.toString(),
            "slaSeconds" to settings.slaSeconds.toString(),
            "targetSeconds" to settings.targetSeconds.toString(),
            "statsOrders" to settings.statsOrders.toString(),
            "statsDays" to settings.statsDays.toString(),
            "maxAttempts" to settings.maxAttempts.toString(),
            "individualLimitMinor" to settings.individualLimitMinor.toString(),
            "businessLimitMinor" to settings.businessLimitMinor.toString(),
            "logRetentionDays" to settings.logRetentionDays.toString(),
        )
}

/** The routing log, newest first: a merchant's decisions, an order's, a stretch of time, or all of a network's. */
class GetRampRoutingDecisions(private val engine: RampEngineAdmin) {
    suspend operator fun invoke(
        query: OpsRoutingQuery,
        after: OpsRoutingCursor?,
        limit: Int,
    ): Resource<List<OpsRoutingDecision>> = engine.decisions(query, after, limit)
}

/** One routing in full: who was looked at, how each scored or why it was left out, and what happened. */
class GetRampRoutingDecision(private val engine: RampEngineAdmin) {
    suspend operator fun invoke(decisionId: UUID): Resource<OpsRoutingDecision> = engine.decision(decisionId)
}

/** What a draft of the engine's settings would do to the last real orders. Nothing is written, so nothing is audited. */
class SimulateRampRouting(private val engine: RampEngineAdmin) {
    suspend operator fun invoke(request: OpsSimulationCommand): Resource<OpsSimulation> = engine.simulate(request)
}

/**
 * Sets a ramp merchant's type and their own order limit. A reason is required and audited, with the type and limit
 * before and after.
 */
class SetRampMerchantTier(private val engine: RampEngineAdmin, private val trail: AuditTrail) {
    suspend operator fun invoke(actor: Actor, update: RampMerchantTierUpdate): Resource<OpsMerchant> {
        val why = Reasons.check(update.reason) ?: return Resource.error(OpsError.ReasonRequired)
        return trail.record<OpsMerchant>(
            actor,
            AuditAction.RAMP_MERCHANT_TIER,
            update.merchantId.toString(),
            reason = why,
            detail = "type ${update.merchantType}, limit ${update.orderLimitMinor ?: "default"}",
            outcome = { "type ${it.merchantType}, limit ${it.orderLimitMinor ?: "default"}" },
        ) { engine.setMerchantTier(update.merchantId, update.merchantType, update.orderLimitMinor, actor.staffId) }
    }
}

/** A merchant's ranking signals on a network: the record the engine scores them on, per direction. */
class GetRampMerchantSignals(private val engine: RampEngineAdmin) {
    suspend operator fun invoke(merchantId: MerchantId, network: Network): Resource<OpsMerchantSignals> =
        engine.merchantSignals(merchantId, network)
}
