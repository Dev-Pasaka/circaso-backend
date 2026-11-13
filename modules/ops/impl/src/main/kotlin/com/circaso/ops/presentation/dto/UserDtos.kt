package com.circaso.ops.presentation.dto

import kotlinx.serialization.Serializable

@Serializable
data class UserSummaryDto(
    val userId: String,
    val displayName: String? = null,
    val handle: String? = null,
    val status: String,
    val kind: String,
    val maskedPhone: String? = null,
    val createdAt: String,
)

@Serializable
data class UserPageResponse(val items: List<UserSummaryDto>, val nextCursor: String? = null)

@Serializable
data class SignInDto(val provider: String, val maskedEmail: String? = null, val linkedAt: String)

@Serializable
data class SuspensionDto(val since: String, val reason: String? = null, val by: String)

@Serializable
data class UserAccountResponse(
    val userId: String,
    val displayName: String? = null,
    val handle: String? = null,
    val about: String? = null,
    val avatarUrl: String? = null,
    val status: String,
    val kind: String,
    val verification: String,
    val maskedPhone: String? = null,
    val createdAt: String,
    val signIns: List<SignInDto>,
    val suspension: SuspensionDto? = null,
)

@Serializable
data class DeviceDto(
    val deviceId: String,
    val platform: String,
    val model: String,
    val appVersion: String,
    val status: String,
    val registeredAt: String,
    val lastSeenAt: String,
    val active: Boolean,
)

@Serializable
data class SessionDto(
    val sessionId: String,
    val deviceId: String,
    val scope: String,
    val status: String,
    val createdAt: String,
    val rotatedAt: String,
    val expiresAt: String,
)

@Serializable
data class UserDevicesResponse(val devices: List<DeviceDto>, val sessions: List<SessionDto>)

/** Suspend or unsuspend: why, 3 to 500 characters. It is kept in the audit log. */
@Serializable
data class ReasonRequest(val reason: String = "")

/** Unfinished sign-ups a clean-up would delete now (no phone yet, started over an hour ago). */
@Serializable
data class PendingSignUpsResponse(val count: Long)

@Serializable
data class PendingSignUpsDeletedResponse(val deleted: Int)
