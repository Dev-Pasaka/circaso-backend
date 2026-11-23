package com.circaso.ramp.domain.repositories

import com.circaso.integrations.api.KycEnvironment
import com.circaso.ramp.domain.models.Block
import com.circaso.ramp.domain.models.PageCursor
import com.circaso.ramp.domain.models.Subject
import com.circaso.ramp.domain.models.SubjectKind
import com.circaso.ramp.domain.models.Verification
import com.circaso.ramp.domain.models.VerificationChange
import com.circaso.ramp.domain.models.VerificationStatus
import com.circaso.ramp.domain.models.VerifiedOutcome
import com.circaso.shared.UserId
import java.time.Instant
import java.util.UUID

/** `ramp.verifications`: one row per subject and provider environment. */
interface VerificationRepository {
    suspend fun find(subject: Subject, environment: KycEnvironment): Verification?

    /** The row whose current session is [sessionId]: how a webhook finds it. */
    suspend fun bySession(sessionId: String): Verification?

    /** The subject's verification in every environment they have one. */
    suspend fun ofSubject(subject: Subject): List<Verification>

    /**
     * Writes the row as it is once a new session has been started: inserted the first time, replaced whole after
     * that (the session, the status, the counts). The identity key of an earlier approval is kept.
     */
    suspend fun save(verification: Verification)

    /**
     * The guard every outcome but an approval goes through: moves the row to [change] only while its current session
     * is still [sessionId] and it isn't already exactly there (the same status and reason). The moved row, or null
     * when it was already there or names another session now: only the caller that moved it tells anyone.
     */
    suspend fun move(
        subject: Subject,
        environment: KycEnvironment,
        sessionId: String,
        change: VerificationChange,
        at: Instant,
    ): Verification?

    /**
     * Marks the row `VERIFIED` under the same guard, claiming [identityKey] when there is one (the live environment):
     * the unique index lets one identity verify one subject of each kind, so a second account presenting the same
     * document loses the claim and nothing is written. Clears the session's link and stamps `verified_at`.
     */
    suspend fun verify(
        subject: Subject,
        environment: KycEnvironment,
        sessionId: String,
        identityKey: String?,
        at: Instant,
    ): VerifiedOutcome

    /** Notes that the provider was asked about the row's session just now. */
    suspend fun touch(
        subject: Subject,
        environment: KycEnvironment,
        at: Instant,
    )

    /** Rows in one of [statuses] the provider wasn't asked about since [checkedBefore], least recently asked first. */
    suspend fun stale(
        statuses: Set<VerificationStatus>,
        checkedBefore: Instant,
        limit: Int,
    ): List<Verification>

    /** Removes the subject's row in [environment], freeing its identity key; the row that was there, or null. */
    suspend fun delete(subject: Subject, environment: KycEnvironment): Verification?
}

/** `ramp.blocks`: users, and KYC identities, barred from the ramp. */
interface BlockRepository {
    suspend fun ofUser(userId: UserId): Block?

    /** Whether [userId] is barred, as a user or (when [identityKey] is given) as that identity. */
    suspend fun isBlocked(userId: UserId, identityKey: String?): Boolean

    suspend fun hasIdentity(identityKey: String): Boolean

    /** True when the row was added; false when that user or identity was already barred. */
    suspend fun add(block: Block): Boolean

    /** Lifts the user's block and every identity block that came from it; how many rows went. */
    suspend fun removeUser(userId: UserId): Int

    /** Barred users, newest first, after [after]. */
    suspend fun users(after: PageCursor?, limit: Int): List<Block>
}
