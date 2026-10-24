package com.circaso.identity.api

import com.circaso.shared.DeviceId
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import java.util.UUID

@JvmInline
value class SessionId(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): SessionId = SessionId(Uuid7.generate())

        fun parse(raw: String): SessionId? = Uuid7.parse(raw)?.let(::SessionId)
    }
}

/** Who a verified access token belongs to. */
data class Principal(
    val userId: UserId,
    val deviceId: DeviceId,
    val sessionId: SessionId,
    val scope: Scope,
)

/** The gateway, on every request. */
interface TokenVerifier {
    /** Checks the signature and expiry locally, then identity's deny-list of revoked sessions. */
    suspend fun verify(accessToken: String): Resource<Principal>
}

enum class AccountStatus { PENDING_PHONE, ACTIVE, RECOVERY_PENDING, SUSPENDED, BLOCKED, DELETED }

/** Users (contact matching) and later modules. Never hands out a phone number. */
interface AccountDirectory {
    suspend fun status(userId: UserId): AccountStatus?

    /** Of the numbers given, those that belong to active accounts. */
    suspend fun resolvePhones(phones: Set<PhoneNumber>): Map<PhoneNumber, UserId>
}

/** Device-bound actions; wallet transactions are signed with the Circle PIN instead. */
interface DeviceSignatures {
    suspend fun verify(
        userId: UserId,
        deviceId: DeviceId,
        payload: ByteArray,
        signature: ByteArray,
    ): Resource<Unit>
}

/**
 * Implemented by the modules above identity (wallet first), wired in `app`. Identity asks every guard before
 * scheduling a deletion and never depends on those modules.
 */
interface DeletionGuard {
    /** An error carries the guard's own code, for example `wallet.funds_remaining`. */
    suspend fun canDelete(userId: UserId): Resource<Unit>
}

/** Where to push a notification for a user; the gateway's fallback when the user has no open socket. */
interface PushRegistrations {
    /** The active phone's push token, or null when there is none. */
    suspend fun pushTarget(userId: UserId): PushRegistration?
}

/** [platform] is `android` or `ios`; [token] is the FCM or APNs token. */
data class PushRegistration(val platform: String, val token: String)
