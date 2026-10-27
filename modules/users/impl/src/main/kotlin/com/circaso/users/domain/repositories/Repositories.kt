package com.circaso.users.domain.repositories

import com.circaso.identity.api.AccountStatus
import com.circaso.shared.EventEnvelope
import com.circaso.shared.UserId
import com.circaso.users.domain.models.Block
import com.circaso.users.domain.models.Contact
import com.circaso.users.domain.models.HandleClaim
import com.circaso.users.domain.models.Invite
import com.circaso.users.domain.models.Profile
import com.circaso.users.domain.models.WaitingContact
import java.time.Instant
import java.util.UUID

interface ProfileRepository {
    suspend fun find(userId: UserId): Profile?

    suspend fun findAll(ids: Set<UserId>): Map<UserId, Profile>

    /** Creates [profile] unless the user already has one, so two first reads can't create two; true if this one did. */
    suspend fun create(profile: Profile): Boolean

    /** Optimistic lock on `version`: the saved profile one version on, or null if it changed since [old] was read. */
    suspend fun update(old: Profile, new: Profile): Profile?

    /**
     * Reads the profile `FOR KEY SHARE`, inside the caller's transaction: until it commits, nobody can delete the profile
     * (an account deletion locks it first too), so contacts are never written for a profile that is going away.
     */
    suspend fun lock(userId: UserId): Profile?

    /**
     * Records the account's [status] as of [at] (the event's time), which hides the profile unless it is `ACTIVE`. An
     * older status than the one recorded changes nothing; true when the row changed.
     */
    suspend fun recordAccountStatus(
        userId: UserId,
        status: AccountStatus,
        at: Instant,
    ): Boolean
}

interface HandleRepository {
    /** The live claim on [handle], or its hold for the old owner while the hold lasts. */
    suspend fun find(handle: String): HandleClaim?

    /**
     * Claims [handle] for [userId] at [at]: a free handle, the caller's own hold or an expired hold. False when someone
     * else owns it or holds it.
     */
    suspend fun claim(
        handle: String,
        userId: UserId,
        at: Instant,
    ): Boolean
}

interface ContactRepository {
    suspend fun find(ownerId: UserId, contactId: UserId): Contact?

    /** The owner's rows for [contactIds] that exist, active or removed. */
    suspend fun findAll(ownerId: UserId, contactIds: Set<UserId>): Map<UserId, Contact>

    /**
     * A page in contact-id order, [after] being the last contact id of the previous page. [activeOnly] leaves out
     * removed rows and people whose account isn't active (their profile is hidden).
     */
    suspend fun list(
        ownerId: UserId,
        after: UUID?,
        limit: Int,
        activeOnly: Boolean,
    ): List<Contact>

    /** Who has [contactId] as an active contact, for fan-out. */
    suspend fun ownersOf(contactId: UserId, limit: Int): List<UserId>

    /**
     * U11: makes [contact] active, adding the row or restoring a removed one (keeping its first `addedAt`). True only
     * when it changed something, so of two connects at once exactly one is told it created the contact.
     */
    suspend fun connect(contact: Contact): Boolean

    /** U16: marks the active contact removed at [at]; false when there is no active contact to remove. */
    suspend fun remove(
        ownerId: UserId,
        contactId: UserId,
        at: Instant,
    ): Boolean
}

interface WaitingContactRepository {
    /** Everyone waiting for the number with this HMAC. */
    suspend fun waitingFor(phoneHmac: String): List<WaitingContact>
}

interface BlockRepository {
    suspend fun blocked(userId: UserId, blockedId: UserId): Boolean

    /** True if either of the two blocked the other. */
    suspend fun eitherWay(a: UserId, b: UserId): Boolean

    suspend fun list(userId: UserId): List<Block>

    /** U14: true when this call added the block, false when it was already there. */
    suspend fun add(block: Block): Boolean

    /** U14: true when this call lifted the block, false when there was none. */
    suspend fun remove(userId: UserId, blockedId: UserId): Boolean
}

interface InviteRepository {
    /** An invite that is neither revoked nor expired. */
    suspend fun find(token: String): Invite?

    /** U12: deletes [token] if [ownerId] owns it; false when there was no such invite of theirs. */
    suspend fun revoke(token: String, ownerId: UserId): Boolean
}

/** Everything one operation writes, with its outbox rows. */
data class UsersChanges(
    /** Added only where the pair has no row yet, so a contact its owner removed stays removed (sync, joins). */
    val contacts: List<Contact> = emptyList(),
    val invites: List<Invite> = emptyList(),
    /** Written or renewed for another 180 days. */
    val waiting: List<WaitingContact> = emptyList(),
    val clearedWaiting: List<WaitingContact> = emptyList(),
    /** Handles to put on a 30-day hold for their old owner. */
    val releasedHandles: List<HandleClaim> = emptyList(),
    /** Deleted accounts: the profile, every contact and block either way, and their invites. */
    val erasedUsers: List<UserId> = emptyList(),
    val events: List<EventEnvelope> = emptyList(),
)

interface UsersStore {
    /**
     * Writes [changes] in the transaction a use case has open (or in one of its own), outbox rows included, so all of
     * it lands or none does. Publishing the events is the caller's, after the commit.
     */
    suspend fun commit(changes: UsersChanges)
}
