package com.circaso.identity.data.mappers

import com.circaso.identity.api.AccountStatus
import com.circaso.identity.api.SessionId
import com.circaso.identity.data.entities.AccountRow
import com.circaso.identity.data.entities.DeviceRow
import com.circaso.identity.data.entities.ProviderLinkRow
import com.circaso.identity.data.entities.SecurityEventRow
import com.circaso.identity.data.entities.SessionRow
import com.circaso.identity.data.entities.SupportRecoveryRow
import com.circaso.identity.domain.models.Account
import com.circaso.identity.domain.models.Device
import com.circaso.identity.domain.models.DeviceStatus
import com.circaso.identity.domain.models.ProviderLink
import com.circaso.identity.domain.models.RecoveryStatus
import com.circaso.identity.domain.models.SecurityEvent
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.domain.models.Session
import com.circaso.identity.domain.models.SessionStatus
import com.circaso.identity.domain.models.SupportRecovery
import com.circaso.identity.domain.models.Verification
import com.circaso.integrations.api.SocialProvider
import com.circaso.shared.DeviceId
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Scope
import com.circaso.shared.UserId
import java.util.UUID

// A plain save writes nothing for a null column, so clearing a value would leave the old one in place. These
// columns store an empty text or the nil UUID for "none" instead, and read it back as null.
private val NO_DEVICE = UUID(0, 0)

fun AccountRow.toModel() =
    Account(
        userId = UserId(userId),
        phone = phone?.takeIf { it.isNotEmpty() }?.let(PhoneNumber::parse),
        status = AccountStatus.valueOf(status),
        activeDeviceId = activeDeviceId?.takeIf { it != NO_DEVICE }?.let(::DeviceId),
        verification = Verification.valueOf(verification),
        sessionVersion = sessionVersion,
        createdAt = createdAt,
        version = version,
    )

fun Account.toRow() =
    AccountRow(
        userId = userId.value,
        phone = phone?.e164 ?: "",
        status = status.name,
        activeDeviceId = activeDeviceId?.value ?: NO_DEVICE,
        verification = verification.name,
        sessionVersion = sessionVersion,
        createdAt = createdAt,
    )

fun ProviderLinkRow.toModel() =
    ProviderLink(
        UserId(userId),
        SocialProvider.valueOf(provider.uppercase()),
        subject,
        email,
        linkedAt,
    )

fun ProviderLink.toRow() = ProviderLinkRow(userId.value, provider.name.lowercase(), subject, email, linkedAt)

fun DeviceRow.toModel() =
    Device(
        UserId(userId),
        DeviceId(deviceId),
        platform,
        model,
        appVersion,
        publicKey,
        keyAlgorithm,
        pushToken,
        DeviceStatus.valueOf(status),
        registeredAt,
        lastSeenAt,
    )

fun Device.toRow() =
    DeviceRow(
        userId.value,
        deviceId.value,
        platform,
        model,
        appVersion,
        publicKey,
        keyAlgorithm,
        pushToken,
        status.name,
        registeredAt,
        lastSeenAt,
    )

fun SessionRow.toModel() =
    Session(
        SessionId(sessionId),
        UserId(userId),
        DeviceId(deviceId),
        currentTokenHash,
        Scope.fromWire(scope) ?: Scope.APP,
        SessionStatus.valueOf(status),
        createdAt,
        rotatedAt,
    )

fun Session.toRow() =
    SessionRow(
        sessionId.value,
        userId.value,
        deviceId.value,
        currentTokenHash,
        scope.wire,
        status.name,
        createdAt,
        rotatedAt,
    )

fun SecurityEventRow.toModel() =
    SecurityEvent(
        UserId(userId),
        eventId,
        runCatching {
            SecurityEventType.valueOf(type)
        }.getOrDefault(SecurityEventType.SIGN_IN),
        deviceModel,
        detail,
    )

fun SecurityEvent.toRow() = SecurityEventRow(userId.value, eventId, type.name, deviceModel, detail)

fun SupportRecoveryRow.toModel() =
    SupportRecovery(
        UserId(userId),
        requestId,
        RecoveryStatus.valueOf(status),
        contactEmail,
        note,
        idCheck,
        selfieMatch,
        agentId,
        noticeSentAt,
        relinkAfter,
        newProvider?.let { SocialProvider.valueOf(it.uppercase()) },
        newSubject,
    )

fun SupportRecovery.toRow() =
    SupportRecoveryRow(
        userId.value,
        requestId,
        status.name,
        contactEmail,
        note,
        idCheck,
        selfieMatch,
        agentId,
        noticeSentAt,
        relinkAfter,
        newProvider?.name?.lowercase(),
        newSubject,
    )
