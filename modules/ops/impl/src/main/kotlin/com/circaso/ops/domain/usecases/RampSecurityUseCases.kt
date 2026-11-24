package com.circaso.ops.domain.usecases

import com.circaso.ops.domain.errors.OpsError
import com.circaso.ops.domain.models.Actor
import com.circaso.ops.domain.models.AuditAction
import com.circaso.ops.domain.support.AuditTrail
import com.circaso.ops.domain.support.Reasons
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.OpsMerchantSecurity
import com.circaso.ramp.api.OpsSecuritySettings
import com.circaso.ramp.api.RampSecurityAdmin
import com.circaso.shared.Resource

/** A ramp merchant's security: the withdrawals lock, their passkeys and recent emails (2026-11-24). */
class GetRampMerchantSecurity(private val security: RampSecurityAdmin) {
    suspend operator fun invoke(merchantId: MerchantId): Resource<OpsMerchantSecurity> =
        security.merchantSecurity(merchantId)
}

/** Lifts a merchant's withdrawals lock, with a reason, audited (2026-11-24). */
class UnlockRampMerchantWithdrawals(private val security: RampSecurityAdmin, private val trail: AuditTrail) {
    suspend operator fun invoke(
        actor: Actor,
        merchantId: MerchantId,
        reason: String,
    ): Resource<OpsMerchantSecurity> {
        val why = Reasons.check(reason) ?: return Resource.error(OpsError.ReasonRequired)
        return trail.record(actor, AuditAction.RAMP_MERCHANT_WITHDRAWALS_UNLOCK, merchantId.toString(), reason = why) {
            security.unlockWithdrawals(merchantId, actor.staffId, why)
        }
    }
}

/** The merchant security settings. */
class GetRampSecuritySettings(private val security: RampSecurityAdmin) {
    suspend operator fun invoke(): Resource<OpsSecuritySettings> = security.securitySettings()
}

/** Changes the merchant security settings, version-checked, with a reason, audited (2026-11-24). */
class UpdateRampSecuritySettings(private val security: RampSecurityAdmin, private val trail: AuditTrail) {
    suspend operator fun invoke(
        actor: Actor,
        passkeyRequired: Boolean,
        version: Long,
        reason: String,
    ): Resource<OpsSecuritySettings> {
        val why = Reasons.check(reason) ?: return Resource.error(OpsError.ReasonRequired)
        return trail.record(
            actor,
            AuditAction.RAMP_SECURITY_SETTINGS_UPDATE,
            "merchant_passkey_required",
            reason = why,
            detail = "merchantPasskeyRequired → $passkeyRequired",
        ) {
            security.updateSecuritySettings(passkeyRequired, version, actor.staffId, why)
        }
    }
}
