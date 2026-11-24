package com.circaso.ops.domain.usecases

import com.circaso.ops.domain.errors.OpsError
import com.circaso.ops.domain.models.Actor
import com.circaso.ops.domain.models.AuditAction
import com.circaso.ops.domain.models.RampPricingUpdate
import com.circaso.ops.domain.support.AuditTrail
import com.circaso.ops.domain.support.Reasons
import com.circaso.ramp.api.OpsPricingChange
import com.circaso.ramp.api.OpsPricingSettings
import com.circaso.ramp.api.OpsRates
import com.circaso.ramp.api.RampRatesAdmin
import com.circaso.shared.Network
import com.circaso.shared.Resource

/** A network's pricing settings: where its buy and sell rates come from. */
class GetRampPricing(private val rates: RampRatesAdmin) {
    suspend operator fun invoke(network: Network): Resource<OpsPricingSettings> = rates.pricing(network)
}

/**
 * Replaces a network's pricing settings with the next version (decision 2026-11-24). A reason is required; the audit
 * line says what changed, by name.
 */
class UpdateRampPricing(private val rates: RampRatesAdmin, private val trail: AuditTrail) {
    suspend operator fun invoke(actor: Actor, update: RampPricingUpdate): Resource<OpsPricingChange> {
        val why = Reasons.check(update.reason) ?: return Resource.error(OpsError.ReasonRequired)
        return trail.record<OpsPricingChange>(
            actor,
            AuditAction.RAMP_PRICING_UPDATE,
            update.settings.network.name,
            reason = why,
            detail = "mode ${update.settings.mode}",
            outcome = { describe(it) },
        ) {
            rates.updatePricing(update.settings, update.version, why, actor.staffId)
        }
    }

    private fun describe(change: OpsPricingChange): String {
        val parts = mutableListOf("v${change.before.version}→v${change.after.version}")
        val before = fields(change.before)
        fields(change.after).forEach { (name, now) ->
            val was = before[name]
            if (was != now) parts += "$name $was→$now"
        }
        return parts.joinToString("; ")
    }

    private fun fields(settings: OpsPricingSettings): Map<String, String> =
        linkedMapOf(
            "mode" to settings.mode,
            "buySpreadBps" to settings.buySpreadBps.toString(),
            "sellSpreadBps" to settings.sellSpreadBps.toString(),
            "manualBuyRate" to settings.manualBuyRate.toString(),
            "manualSellRate" to settings.manualSellRate.toString(),
            "manualExpiresAt" to settings.manualExpiresAt.toString(),
            "maxDeviationBps" to settings.maxDeviationBps.toString(),
            "maxReferenceAgeHours" to settings.maxReferenceAgeHours.toString(),
        )
}

/** A network's rates now: the reference and its age, the buy and sell rates in force or why each is paused. */
class GetRampRates(private val rates: RampRatesAdmin) {
    suspend operator fun invoke(network: Network): Resource<OpsRates> = rates.rates(network)
}
