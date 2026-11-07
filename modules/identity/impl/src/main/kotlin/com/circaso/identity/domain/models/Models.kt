package com.circaso.identity.domain.models

import com.circaso.identity.api.AccountKind
import com.circaso.identity.api.AccountStatus
import com.circaso.identity.api.SessionId
import com.circaso.integrations.api.SocialProvider
import com.circaso.shared.DeviceId
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Scope
import com.circaso.shared.UserId
import java.time.Instant
import java.util.UUID

enum class Verification { NONE, PENDING, VERIFIED }

data class Account(
    val userId: UserId,
    val phone: PhoneNumber?,
    val status: AccountStatus,
    val activeDeviceId: DeviceId?,
    val verification: Verification,
    val sessionVersion: Int,
    val createdAt: Instant,
    /** Set when the account turns `DELETED`: when its grace period ends. */
    val deleteAfter: Instant? = null,
    /** The row's version when it was read; a write checks it, so a stale copy never overwrites a newer one. */
    val version: Long = 0,
    /** Set at creation and never changed: a support account can't become a person's, or the other way round. */
    val kind: AccountKind = AccountKind.PERSON,
) {
    /** A support account never gets an `app` session (decision 2026-10-31); ops reaches it instead. */
    val isSupport: Boolean get() = kind == AccountKind.SUPPORT
}

data class ProviderLink(
    val userId: UserId,
    val provider: SocialProvider,
    val subject: String,
    val email: String?,
    val linkedAt: Instant,
)

enum class DeviceStatus { ACTIVE, REPLACED, REMOVED }

data class Device(
    val userId: UserId,
    val deviceId: DeviceId,
    val platform: String,
    val model: String,
    val appVersion: String,
    val publicKey: String,
    val keyAlgorithm: String,
    val pushToken: String?,
    /** The iPhone's PushKit token, for incoming-call rings sent straight to APNs; null elsewhere. */
    val voipToken: String?,
    val status: DeviceStatus,
    val registeredAt: Instant,
    val lastSeenAt: Instant,
)

enum class SessionStatus { ACTIVE, REVOKED }

data class Session(
    val sessionId: SessionId,
    val userId: UserId,
    val deviceId: DeviceId,
    /** SHA-256 of the live refresh token; the token itself is never stored. */
    val currentTokenHash: String,
    val scope: Scope,
    val status: SessionStatus,
    val createdAt: Instant,
    val rotatedAt: Instant,
)

enum class SecurityEventType {
    SIGN_IN,
    SIGN_OUT,
    NEW_PHONE,
    PHONE_VERIFIED,
    PROVIDER_LINKED,
    PROVIDER_UNLINKED,
    DEVICE_REMOVED,
    DELETION_REQUESTED,
    RECOVERY_REQUESTED,
    PHONE_RELEASE_REQUESTED,
    SESSION_REUSE,
    SUPPORT_ACCOUNT_CREATED,
}

data class SecurityEvent(
    val userId: UserId,
    /** Time-ordered UUID; null until stored. */
    val eventId: UUID?,
    val type: SecurityEventType,
    val deviceModel: String?,
    val detail: String?,
)

enum class RecoveryStatus { RECEIVED, VERIFYING, NOTICE_SENT, RELINKED, REJECTED, CANCELLED }

data class SupportRecovery(
    val userId: UserId,
    val requestId: UUID,
    val status: RecoveryStatus,
    val contactEmail: String,
    val note: String?,
    val idCheck: String?,
    val selfieMatch: String?,
    val agentId: String?,
    val noticeSentAt: Instant?,
    val relinkAfter: Instant?,
    val newProvider: SocialProvider?,
    val newSubject: String?,
)
