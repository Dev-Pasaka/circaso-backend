package com.circaso.identity.api

import com.circaso.shared.DeviceId
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import com.circaso.shared.UuidIdSerializer
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

@Serializable(with = SessionId.Serializer::class)
@JvmInline
value class SessionId(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): SessionId = SessionId(Uuid7.generate())

        fun parse(raw: String): SessionId? = Uuid7.parse(raw)?.let(::SessionId)
    }

    object Serializer : UuidIdSerializer<SessionId>("SessionId", ::SessionId, SessionId::value)
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

    /**
     * [verify], with [alongside] run while the deny-list lookup is on its way, so the gateway's own Redis work (rate
     * limit, idempotency claim) shares the round trip instead of waiting behind it. [alongside] runs only once the
     * signature and expiry check out, with the principal they name, before revocation is known. The first value is
     * what [verify] would answer; the second is [alongside]'s result, null when it didn't run. It is given back even
     * when the session turns out revoked, so the caller can undo what it did.
     *
     * The default runs the two in turn: one round trip after the other, the same answers.
     */
    suspend fun <T> verifyAlongside(
        accessToken: String,
        alongside: suspend (Principal) -> T,
    ): Pair<Resource<Principal>, T?> {
        val verified = verify(accessToken)
        return verified to (verified as? Resource.Success)?.let { alongside(it.data) }
    }
}

enum class AccountStatus { PENDING_PHONE, ACTIVE, RECOVERY_PENDING, SUSPENDED, BLOCKED, DELETED }

/**
 * Whose account it is (decision 2026-10-31). Every account is a [PERSON]'s unless ops made it as Circaso's own
 * [SUPPORT] account, which never signs in on the app and never holds a wallet. The kind is set when the account is
 * created and never changes.
 */
enum class AccountKind { PERSON, SUPPORT }

/** Users (contact matching) and later modules. Never hands out a phone number. */
interface AccountDirectory {
    suspend fun status(userId: UserId): AccountStatus?

    /** Null if there is no such account. Wallet asks it before anything that would give a support account money. */
    suspend fun kind(userId: UserId): AccountKind?

    /** Of the numbers given, those that belong to active accounts. */
    suspend fun resolvePhones(phones: Set<PhoneNumber>): Map<PhoneNumber, UserId>

    /**
     * When the account was made, for rules that wait on an account's age (spaces: accounts younger than 7 days can't
     * start one, decision 2026-11-04). Null if there is no such account; implementations that can't say answer null.
     */
    suspend fun createdAt(userId: UserId): Instant? = null
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

/**
 * Implemented by users, wired in `app`. Identity's ops routes create support accounts (decision 2026-10-31), but the
 * profile and the @handle are users', and users sits above identity, so identity asks through this instead of
 * depending on users. Users makes the profile itself from [SupportAccountCreated].
 */
interface SupportProfiles {
    /**
     * The handle in its stored form if a new support account may take it: well-formed and free. Reserved names
     * (`circaso`, `support`, `help`) are allowed here and nowhere else. An error carries users' own code
     * (`users.handle_invalid`, `users.handle_taken`).
     */
    suspend fun checkHandle(handle: String): Resource<String>

    /** The display name and handle of each account given that has a profile, for the ops list. */
    suspend fun describe(ids: Set<UserId>): Map<UserId, SupportProfile>
}

data class SupportProfile(val displayName: String, val handle: String?)
