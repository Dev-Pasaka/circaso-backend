package com.circaso.ops.domain.usecases

import com.circaso.identity.api.AccountAdmin
import com.circaso.ops.domain.errors.OpsError
import com.circaso.ops.domain.models.Actor
import com.circaso.ops.domain.models.AuditAction
import com.circaso.ops.domain.support.AuditTrail
import com.circaso.ops.domain.support.Reasons
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.OpsMerchant
import com.circaso.ramp.api.OpsRampOrder
import com.circaso.ramp.api.OpsRampPreviewAccess
import com.circaso.ramp.api.OpsRampSettings
import com.circaso.ramp.api.OpsRampSettingsChange
import com.circaso.ramp.api.RampAdmin
import com.circaso.ramp.api.RampOrderId
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** The ramp's partner merchants and their states, newest first (decision 2026-11-22). */
class GetRampMerchants(private val ramp: RampAdmin) {
    suspend operator fun invoke(before: MerchantId?, limit: Int): Resource<List<OpsMerchant>> =
        Resource.success(ramp.merchants(before, limit))
}

/**
 * Approves ([approve]) or suspends a ramp merchant. A reason is required and audited. Approval is what lets the ops
 * server make the merchant's float wallet; a suspension takes the merchant offline at once.
 */
class DecideRampMerchant(private val ramp: RampAdmin, private val trail: AuditTrail) {
    suspend operator fun invoke(
        actor: Actor,
        merchantId: MerchantId,
        approve: Boolean,
        reason: String,
    ): Resource<OpsMerchant> {
        val why = Reasons.check(reason) ?: return Resource.error(OpsError.ReasonRequired)
        val action = if (approve) AuditAction.RAMP_MERCHANT_APPROVE else AuditAction.RAMP_MERCHANT_SUSPEND
        return trail.record<OpsMerchant>(
            actor,
            action,
            merchantId.toString(),
            reason = why,
            outcome = { "state ${it.state}" },
        ) { if (approve) ramp.approve(merchantId, actor.staffId) else ramp.suspend(merchantId, actor.staffId) }
    }
}

/** One network's ramp settings, as the database has them now. */
class GetRampSettings(private val ramp: RampAdmin) {
    suspend operator fun invoke(network: Network): Resource<OpsRampSettings> = Resource.success(ramp.settings(network))
}

/**
 * Replaces one network's ramp settings (state, chains, limits, fee and split, sweep hour, windows, KYC, callback
 * origin). A reason is required; the audit line says what changed, by name, never a secret (there is none here).
 */
class UpdateRampSettings(private val ramp: RampAdmin, private val trail: AuditTrail) {
    suspend operator fun invoke(
        actor: Actor,
        settings: OpsRampSettings,
        reason: String,
    ): Resource<OpsRampSettingsChange> {
        val why = Reasons.check(reason) ?: return Resource.error(OpsError.ReasonRequired)
        return trail.record<OpsRampSettingsChange>(
            actor,
            AuditAction.RAMP_SETTINGS_UPDATE,
            settings.network.name,
            reason = why,
            detail = "state ${settings.state}, fee ${settings.feeBps} bps",
            outcome = { describe(it) },
        ) { ramp.updateSettings(settings, actor.staffId) }
    }

    private fun describe(change: OpsRampSettingsChange): String {
        val before = change.before
        val after = change.after
        val parts = mutableListOf("v${before.version}→v${after.version}")

        fun changed(
            name: String,
            was: Any?,
            now: Any?,
        ) {
            if (was != now) parts += "$name $was→$now"
        }
        changed("state", before.state, after.state)
        changed("chains", before.chains.joinToString(","), after.chains.joinToString(","))
        changed("orderMin", before.orderMinMinor, after.orderMinMinor)
        changed("orderMax", before.orderMaxMinor, after.orderMaxMinor)
        changed("dailyLimit", before.dailyLimitMinor, after.dailyLimitMinor)
        changed("promptsPerHour", before.promptsPerHour, after.promptsPerHour)
        changed("feeBps", before.feeBps, after.feeBps)
        changed("merchantShareBps", before.merchantShareBps, after.merchantShareBps)
        changed("feeSettleHourUtc", before.feeSettleHourUtc, after.feeSettleHourUtc)
        changed("quoteSeconds", before.quoteSeconds, after.quoteSeconds)
        changed("paymentWindowSeconds", before.paymentWindowSeconds, after.paymentWindowSeconds)
        changed("kycRequired", before.kycRequired, after.kycRequired)
        changed("callbackBaseUrl", before.callbackBaseUrl, after.callbackBaseUrl)
        return parts.joinToString("; ")
    }
}

/** Who may trade on a network while its ramp is in PREVIEW. */
class GetRampPreviewAccess(private val ramp: RampAdmin) {
    suspend operator fun invoke(network: Network): Resource<List<OpsRampPreviewAccess>> =
        Resource.success(ramp.previewAccess(network))
}

/**
 * Puts a person on a network's ramp preview list, or takes them off. A reason is required and audited; the person
 * must exist. Shillings are real on both networks, so the list is who may spend them while the ramp is being tried.
 */
class SetRampPreviewAccess(
    private val accounts: AccountAdmin,
    private val ramp: RampAdmin,
    private val trail: AuditTrail,
) {
    suspend operator fun invoke(
        actor: Actor,
        userId: UserId,
        network: Network,
        enabled: Boolean,
        reason: String,
    ): Resource<List<OpsRampPreviewAccess>> {
        val why = Reasons.check(reason) ?: return Resource.error(OpsError.ReasonRequired)
        accounts.account(userId) ?: return Resource.error(OpsError.UserNotFound)
        val word = if (enabled) "grant" else "revoke"
        return trail.record<List<OpsRampPreviewAccess>>(
            actor,
            AuditAction.RAMP_PREVIEW_ACCESS,
            userId.toString(),
            reason = why,
            detail = "$word $network",
        ) {
            ramp.setPreviewAccess(userId, network, enabled, actor.staffId)
            Resource.success(ramp.previewAccess(network))
        }
    }
}

/** The ramp's orders waiting for a person (`REVIEW`), oldest first. */
class GetRampReviewOrders(private val ramp: RampAdmin) {
    suspend operator fun invoke(
        network: Network?,
        after: RampOrderId?,
        limit: Int,
    ): Resource<List<OpsRampOrder>> = Resource.success(ramp.ordersInReview(network, after, limit))
}
