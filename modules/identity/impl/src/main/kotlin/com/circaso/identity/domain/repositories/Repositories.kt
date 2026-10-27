package com.circaso.identity.domain.repositories

import com.circaso.identity.api.SessionId
import com.circaso.identity.domain.models.Account
import com.circaso.identity.domain.models.Device
import com.circaso.identity.domain.models.ProviderLink
import com.circaso.identity.domain.models.SecurityEvent
import com.circaso.identity.domain.models.Session
import com.circaso.identity.domain.models.SupportRecovery
import com.circaso.integrations.api.SocialProvider
import com.circaso.shared.DeviceId
import com.circaso.shared.EventEnvelope
import com.circaso.shared.PhoneNumber
import com.circaso.shared.UserId
import java.time.Instant
import java.util.UUID

interface AccountRepository {
    suspend fun find(userId: UserId): Account?

    /**
     * The account, locked until the open transaction ends (`SELECT … FOR UPDATE`). Every operation that changes an
     * account, its devices, sessions or logins takes this lock first (the lock-order rule), so two requests for the
     * same person run one after the other and each decides on what the other committed. Only inside
     * `Transactor.run`.
     */
    suspend fun lock(userId: UserId): Account?

    /** The account that holds [phone], if any. */
    suspend fun findByPhone(phone: PhoneNumber): Account?

    /** A new account, written in the transaction that claims its Google/Apple login. */
    suspend fun create(account: Account)

    /**
     * Claims [phone] for [account] (read under its lock, at its version) through the unique index on the number. True
     * when the number is now the account's; false when another account holds it. A false answer leaves the transaction
     * unusable (the database refused the row), so the caller returns an error from `Transactor.attempt`, which rolls
     * everything back.
     */
    suspend fun claimPhone(account: Account, phone: PhoneNumber): Boolean

    /**
     * Deletes up to [limit] accounts still `PENDING_PHONE` that were created before [createdBefore], with their
     * logins, devices, sessions and security log, in one transaction. Rows another transaction holds are skipped
     * this time. Returns how many accounts went; their numbers are free again.
     */
    suspend fun deleteAbandoned(createdBefore: Instant, limit: Int): Int

    /** Up to [limit] `DELETED` accounts whose grace period ended by [now], oldest first. */
    suspend fun dueForDeletion(now: Instant, limit: Int): List<UserId>

    /**
     * Deletes the account and every identity row of it (logins, devices, sessions, security log, support cases), but
     * only if it is still `DELETED` with its grace ended by [now] (`DELETE … RETURNING`, so of two jobs racing, one
     * deletes and the other finds nothing). True when this call deleted it. Inside `Transactor.run`, with the event.
     */
    suspend fun completeDeletion(userId: UserId, now: Instant): Boolean
}

interface ProviderRepository {
    /** The account that owns a Google/Apple login. */
    suspend fun ownerOf(provider: SocialProvider, subject: String): UserId?

    /**
     * Claims [link]'s login for its account through the unique `(provider, subject)` index (`INSERT … ON CONFLICT DO
     * NOTHING`). True when the login is now the account's: newly written, already its own, or replacing the account's
     * other login of the same provider. False when another account holds it; a false answer may leave the transaction
     * unusable, so the caller returns an error from `Transactor.attempt`.
     */
    suspend fun claim(link: ProviderLink): Boolean

    suspend fun links(userId: UserId): List<ProviderLink>
}

interface DeviceRepository {
    suspend fun find(userId: UserId, deviceId: DeviceId): Device?
}

interface SessionRepository {
    /** The session, unless it has expired (30 days after its last rotation). */
    suspend fun find(sessionId: SessionId): Session?

    /**
     * Ends the session if it is still active (`UPDATE … WHERE status = 'ACTIVE' RETURNING`): the revoked session, or
     * null when it had already ended, expired or never existed. Exactly one caller sees it end.
     */
    suspend fun revoke(sessionId: SessionId): Session?

    /** Ends every active session of [userId] the same way; the ones this call ended, for the gateway's deny-list. */
    suspend fun revokeAll(userId: UserId): List<SessionId>
}

interface SecurityLogRepository {
    /** Newest first; [before] is the last event id of the previous page (keyset paging). */
    suspend fun list(
        userId: UserId,
        before: UUID?,
        limit: Int,
    ): List<SecurityEvent>
}

interface RecoveryRepository {
    suspend fun find(requestId: UUID): SupportRecovery?
}

/** Everything one operation changes, written in one transaction together with its outbox rows. */
data class Changes(
    /** Accounts read in this transaction; each write checks the version it was read at. */
    val accounts: List<Account> = emptyList(),
    val devices: List<Device> = emptyList(),
    val sessions: List<Session> = emptyList(),
    val removedProviderLinks: List<ProviderLink> = emptyList(),
    val securityEvents: List<SecurityEvent> = emptyList(),
    val recoveries: List<SupportRecovery> = emptyList(),
    /** Written to the outbox in the same transaction, then published once it commits. */
    val events: List<EventEnvelope> = emptyList(),
)

interface IdentityStore {
    /**
     * Writes [changes] and their outbox rows inside the open transaction. An account in [Changes.accounts] keeps its
     * number; a number is claimed only through `AccountRepository.claimPhone`.
     */
    suspend fun write(changes: Changes)

    /** Right after the commit: puts the events on the stream (a failure is left to the outbox relay). */
    suspend fun publish(changes: Changes)

    /** A convenience for an operation with no decision to make inside the transaction: [write] in one, then [publish]. */
    suspend fun commit(changes: Changes)
}
