package com.circaso.ramp.presentation.api

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.OpsMerchantAlert
import com.circaso.ramp.api.OpsMerchantPasskey
import com.circaso.ramp.api.OpsMerchantSecurity
import com.circaso.ramp.api.OpsSecuritySettings
import com.circaso.ramp.api.RampSecurityAdmin
import com.circaso.ramp.domain.models.SecuritySettings
import com.circaso.ramp.domain.models.StaffSecurity
import com.circaso.ramp.presentation.services.RampSecurityService
import com.circaso.shared.Resource
import com.circaso.shared.flatMap
import com.circaso.shared.map
import java.util.UUID

/** [RampSecurityAdmin] for the ops module: each call is one service method, mapped to the contract's types. */
class RampSecurityAdminImpl(private val security: RampSecurityService) : RampSecurityAdmin {
    override suspend fun merchantSecurity(merchantId: MerchantId): Resource<OpsMerchantSecurity> =
        security.merchant(merchantId).map { it.toOps() }

    override suspend fun unlockWithdrawals(
        merchantId: MerchantId,
        by: UUID,
        reason: String,
    ): Resource<OpsMerchantSecurity> =
        security.unlock(merchantId, by.toString(), reason).flatMap { merchantSecurity(merchantId) }

    override suspend fun securitySettings(): Resource<OpsSecuritySettings> = security.settings().map { it.toOps() }

    override suspend fun updateSecuritySettings(
        merchantPasskeyRequired: Boolean,
        version: Long,
        by: UUID,
        reason: String,
    ): Resource<OpsSecuritySettings> =
        security.updateSettings(merchantPasskeyRequired, version, by.toString(), reason).map { it.toOps() }
}

private fun SecuritySettings.toOps() =
    OpsSecuritySettings(merchantPasskeyRequired, version, updatedBy, reason, updatedAt)

private fun StaffSecurity.toOps() =
    OpsMerchantSecurity(
        merchantId = security.merchantId,
        lockedAt = security.lockedAt,
        lockedByLink = security.lockedAlertId != null,
        lockedCancelled = security.lockedCancelled,
        unlockedAt = security.unlockedAt,
        unlockedBy = security.unlockedBy,
        unlockReason = security.unlockReason,
        orderEmails = security.orderEmails,
        dailySummary = security.dailySummary,
        passkeys =
            passkeys.map {
                OpsMerchantPasskey(
                    it.passkeyId,
                    it.label,
                    it.createdAt,
                    it.lastUsedAt,
                    it.backedUp,
                )
            },
        alerts =
            recentAlerts.map {
                OpsMerchantAlert(
                    alertId = it.alertId,
                    kind = it.kind.name,
                    security = it.kind.security,
                    state = it.state.name,
                    failure = it.failure,
                    createdAt = it.createdAt,
                    sentAt = it.sentAt,
                    linkUsedAt = it.linkUsedAt,
                )
            },
    )
