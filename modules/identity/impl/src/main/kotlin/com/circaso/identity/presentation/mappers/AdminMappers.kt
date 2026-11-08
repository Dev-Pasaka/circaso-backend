package com.circaso.identity.presentation.mappers

import com.circaso.identity.api.AccountPage
import com.circaso.identity.api.AdminAccount
import com.circaso.identity.api.AdminDevice
import com.circaso.identity.api.AdminSession
import com.circaso.identity.api.AdminSignIn
import com.circaso.identity.domain.models.AccountRecord
import com.circaso.identity.domain.models.AccountRecordPage
import com.circaso.identity.domain.models.DeviceRecord
import com.circaso.identity.domain.models.SessionRecord

// Staff see accounts masked (decision 2026-11-08): the number as `PhoneNumber.masked()` and emails as Asha's agent
// route shows them ([maskEmail]), so staff confirm a person by what the person tells them, never by reading it out.

fun AccountRecord.toAdmin() =
    AdminAccount(
        userId = account.userId,
        kind = account.kind,
        status = account.status,
        maskedPhone = account.phone?.masked(),
        verification = account.verification.name,
        createdAt = account.createdAt,
        signIns = links.map { AdminSignIn(it.provider.name, it.email?.let(::maskEmail), it.linkedAt) },
    )

fun AccountRecordPage.toAdmin() = AccountPage(accounts.map { it.toAdmin() }, nextCursor)

fun DeviceRecord.toAdmin() =
    AdminDevice(
        deviceId = device.deviceId,
        platform = device.platform,
        model = device.model,
        appVersion = device.appVersion,
        status = device.status.name,
        registeredAt = device.registeredAt,
        lastSeenAt = device.lastSeenAt,
        active = active,
    )

fun SessionRecord.toAdmin() =
    AdminSession(
        sessionId = session.sessionId,
        deviceId = session.deviceId,
        scope = session.scope.wire,
        status = session.status.name,
        createdAt = session.createdAt,
        rotatedAt = session.rotatedAt,
        expiresAt = expiresAt,
    )
