package com.circaso.ops.presentation.dto

import kotlinx.serialization.Serializable

/** A ramp merchant's security for staff (2026-11-24). `locked` with `lockedAt`; `lockedByLink`: the merchant's own link. */
@Serializable
data class RampMerchantSecurityResponse(
    val merchantId: String,
    val locked: Boolean,
    val lockedAt: String?,
    val lockedByLink: Boolean,
    val lockedCancelled: Int,
    val unlockedAt: String?,
    val unlockedBy: String?,
    val unlockReason: String?,
    val orderEmails: Boolean,
    val dailySummary: Boolean,
    val passkeys: List<RampMerchantPasskeyDto>,
    val alerts: List<RampMerchantAlertDto>,
)

@Serializable
data class RampMerchantPasskeyDto(
    val passkeyId: String,
    val label: String,
    val createdAt: String,
    val lastUsedAt: String?,
    val backedUp: Boolean,
)

@Serializable
data class RampMerchantAlertDto(
    val alertId: String,
    val kind: String,
    val security: Boolean,
    val state: String,
    val failure: String?,
    val createdAt: String,
    val sentAt: String?,
    val linkUsedAt: String?,
)

@Serializable
data class RampSecuritySettingsResponse(
    val merchantPasskeyRequired: Boolean,
    val version: Long,
    val updatedBy: String?,
    val reason: String?,
    val updatedAt: String?,
)

@Serializable
data class UpdateRampSecuritySettingsRequest(
    val merchantPasskeyRequired: Boolean = false,
    val version: Long = 0,
    val reason: String = "",
)
