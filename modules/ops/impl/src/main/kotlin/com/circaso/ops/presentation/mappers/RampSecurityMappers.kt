package com.circaso.ops.presentation.mappers

import com.circaso.ops.presentation.dto.RampMerchantAlertDto
import com.circaso.ops.presentation.dto.RampMerchantPasskeyDto
import com.circaso.ops.presentation.dto.RampMerchantSecurityResponse
import com.circaso.ops.presentation.dto.RampSecuritySettingsResponse
import com.circaso.ramp.api.OpsMerchantSecurity
import com.circaso.ramp.api.OpsSecuritySettings

fun OpsMerchantSecurity.toResponse() =
    RampMerchantSecurityResponse(
        merchantId = merchantId.toString(),
        locked = lockedAt != null,
        lockedAt = lockedAt?.toString(),
        lockedByLink = lockedByLink,
        lockedCancelled = lockedCancelled,
        unlockedAt = unlockedAt?.toString(),
        unlockedBy = unlockedBy,
        unlockReason = unlockReason,
        orderEmails = orderEmails,
        dailySummary = dailySummary,
        passkeys =
            passkeys.map {
                RampMerchantPasskeyDto(
                    it.passkeyId.toString(),
                    it.label,
                    it.createdAt.toString(),
                    it.lastUsedAt?.toString(),
                    it.backedUp,
                )
            },
        alerts =
            alerts.map {
                RampMerchantAlertDto(
                    alertId = it.alertId.toString(),
                    kind = it.kind,
                    security = it.security,
                    state = it.state,
                    failure = it.failure,
                    createdAt = it.createdAt.toString(),
                    sentAt = it.sentAt?.toString(),
                    linkUsedAt = it.linkUsedAt?.toString(),
                )
            },
    )

fun OpsSecuritySettings.toResponse() =
    RampSecuritySettingsResponse(merchantPasskeyRequired, version, updatedBy, reason, updatedAt?.toString())
