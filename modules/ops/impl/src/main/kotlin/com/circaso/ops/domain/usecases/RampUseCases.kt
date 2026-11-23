package com.circaso.ops.domain.usecases

import com.circaso.identity.api.AccountAdmin
import com.circaso.ops.domain.errors.OpsError
import com.circaso.ops.domain.models.Actor
import com.circaso.ops.domain.models.AuditAction
import com.circaso.ops.domain.models.RampMerchantOrdersQuery
import com.circaso.ops.domain.support.AuditTrail
import com.circaso.ops.domain.support.Reasons
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.OpsMerchant
import com.circaso.ramp.api.OpsRampBlock
import com.circaso.ramp.api.OpsRampBlockCursor
import com.circaso.ramp.api.OpsRampMerchantOrder
import com.circaso.ramp.api.OpsRampMerchantSummary
import com.circaso.ramp.api.OpsRampOrder
import com.circaso.ramp.api.OpsRampOverview
import com.circaso.ramp.api.OpsRampPeriod
import com.circaso.ramp.api.OpsRampPreviewAccess
import com.circaso.ramp.api.OpsRampSettings
import com.circaso.ramp.api.OpsRampSettingsChange
import com.circaso.ramp.api.OpsRampUserVerification
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
 * Replaces one network's ramp settings (state, chains, limits, fee and split, sweep hour, windows, KYC and how it
 * is run, callback origin, collections provider). A reason is required; the audit line says what changed, by name,
 * never a secret (there is none here).
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
        changed("collectionsProvider", before.collectionsProvider, after.collectionsProvider)
        changed("providerFeeFloorMinor", before.providerFeeFloorMinor, after.providerFeeFloorMinor)
        changed("kycProvider", before.kycProvider, after.kycProvider)
        changed("kycEnvironment", before.kycEnvironment, after.kycEnvironment)
        changed("kycWorkflowUser", before.kycWorkflowUser, after.kycWorkflowUser)
        changed("kycWorkflowMerchant", before.kycWorkflowMerchant, after.kycWorkflowMerchant)
        changed("merchantPortalUrl", before.merchantPortalUrl, after.merchantPortalUrl)
        changed("kycAttemptsPerDay", before.kycAttemptsPerDay, after.kycAttemptsPerDay)
        changed("kycPendingHours", before.kycPendingHours, after.kycPendingHours)
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

/**
 * What every merchant did on a network in a period (Nairobi days; this month when none is given). The figures are the
 * ramp's own, the same ones each merchant sees on their portal; the totals are the sum of the merchant rows.
 */
class GetRampOverview(private val ramp: RampAdmin) {
    suspend operator fun invoke(network: Network, period: OpsRampPeriod?): Resource<OpsRampOverview> =
        Resource.success(ramp.overview(network, period))
}

/** One merchant on a network in a period: the account, the float, the orders, the money, the days and the sweeps. */
class GetRampMerchantSummary(private val ramp: RampAdmin) {
    suspend operator fun invoke(
        merchantId: MerchantId,
        network: Network,
        period: OpsRampPeriod?,
    ): Resource<OpsRampMerchantSummary> = ramp.merchantSummary(merchantId, network, period)
}

/** A merchant's orders on a network, newest first, a page at a time. The payer's number arrives masked. */
class GetRampMerchantOrders(private val ramp: RampAdmin) {
    suspend operator fun invoke(query: RampMerchantOrdersQuery, limit: Int): Resource<List<OpsRampMerchantOrder>> =
        ramp.merchantOrders(query.merchantId, query.network, query.period, query.state, query.after, limit)
}

/** A user's ramp verification in each provider environment, and their block. The person must exist. */
class GetRampUserVerification(private val accounts: AccountAdmin, private val ramp: RampAdmin) {
    suspend operator fun invoke(userId: UserId): Resource<OpsRampUserVerification> {
        accounts.account(userId) ?: return Resource.error(OpsError.UserNotFound)
        return Resource.success(ramp.userVerification(userId))
    }
}

/** The users barred from the ramp, newest first. */
class GetRampBlocks(private val ramp: RampAdmin) {
    suspend operator fun invoke(after: OpsRampBlockCursor?, limit: Int): Resource<List<OpsRampBlock>> =
        Resource.success(ramp.blocks(after, limit))
}

/**
 * Bars a person from the ramp, and the identity they verified with, so they can't come back on a new account. A
 * reason is required: it is audited and kept on the block. The person must exist.
 */
class BlockRampUser(private val accounts: AccountAdmin, private val ramp: RampAdmin, private val trail: AuditTrail) {
    suspend operator fun invoke(
        actor: Actor,
        userId: UserId,
        reason: String,
    ): Resource<OpsRampBlock> {
        val why = Reasons.check(reason) ?: return Resource.error(OpsError.ReasonRequired)
        accounts.account(userId) ?: return Resource.error(OpsError.UserNotFound)
        return trail.record<OpsRampBlock>(
            actor,
            AuditAction.RAMP_BLOCK,
            userId.toString(),
            reason = why,
            outcome = { "blocked (${it.reason})" },
        ) { ramp.block(userId, why, actor.staffId) }
    }
}

/** Lifts a person's ramp block, and the identity blocks that came from it. A reason is required and audited. */
class UnblockRampUser(private val ramp: RampAdmin, private val trail: AuditTrail) {
    suspend operator fun invoke(
        actor: Actor,
        userId: UserId,
        reason: String?,
    ): Resource<OpsRampUserVerification> {
        val why = Reasons.check(reason.orEmpty()) ?: return Resource.error(OpsError.ReasonRequired)
        var lifted = false
        return trail.record<OpsRampUserVerification>(
            actor,
            AuditAction.RAMP_UNBLOCK,
            userId.toString(),
            reason = why,
            outcome = { if (lifted) "unblocked" else "was not blocked" },
        ) {
            lifted = ramp.unblock(userId, actor.staffId)
            Resource.success(ramp.userVerification(userId))
        }
    }
}

/**
 * Removes a user's ramp verification in one provider environment, freeing the identity it holds, so the person can
 * verify again (on this account or another). A reason is required and audited.
 */
class ResetRampUserVerification(private val ramp: RampAdmin, private val trail: AuditTrail) {
    suspend operator fun invoke(
        actor: Actor,
        userId: UserId,
        environment: String,
        reason: String,
    ): Resource<OpsRampUserVerification> {
        val why = Reasons.check(reason) ?: return Resource.error(OpsError.ReasonRequired)
        var removed = false
        return trail.record<OpsRampUserVerification>(
            actor,
            AuditAction.RAMP_VERIFICATION_RESET,
            userId.toString(),
            reason = why,
            detail = "reset ${environment.trim().uppercase()}",
            outcome = { "reset ${environment.trim().uppercase()}: ${if (removed) "removed" else "nothing to remove"}" },
        ) {
            when (val reset = ramp.resetUserVerification(userId, environment, actor.staffId)) {
                is Resource.Error -> {
                    reset
                }

                is Resource.Success -> {
                    removed = reset.data
                    Resource.success(ramp.userVerification(userId))
                }
            }
        }
    }
}

/** The same for a merchant; whether there was a verification to remove. */
class ResetRampMerchantVerification(private val ramp: RampAdmin, private val trail: AuditTrail) {
    suspend operator fun invoke(
        actor: Actor,
        merchantId: MerchantId,
        environment: String,
        reason: String,
    ): Resource<Boolean> {
        val why = Reasons.check(reason) ?: return Resource.error(OpsError.ReasonRequired)
        return trail.record<Boolean>(
            actor,
            AuditAction.RAMP_MERCHANT_VERIFICATION_RESET,
            merchantId.toString(),
            reason = why,
            detail = "reset ${environment.trim().uppercase()}",
            outcome = { "reset ${environment.trim().uppercase()}: ${if (it) "removed" else "nothing to remove"}" },
        ) { ramp.resetMerchantVerification(merchantId, environment, actor.staffId) }
    }
}
