package com.circaso.users.domain.repositories

import com.circaso.shared.EventEnvelope
import com.circaso.shared.UserId
import com.circaso.users.domain.models.Block
import com.circaso.users.domain.models.Contact
import com.circaso.users.domain.models.HandleClaim
import com.circaso.users.domain.models.Invite
import com.circaso.users.domain.models.Profile
import com.circaso.users.domain.models.WaitingContact
import java.util.UUID

interface ProfileRepository {
    suspend fun find(userId: UserId): Profile?

    suspend fun findAll(ids: Set<UserId>): Map<UserId, Profile>

    /** Optimistic lock on `version`: the saved profile, or null if it changed since [old] was read. */
    suspend fun update(old: Profile, new: Profile): Profile?
}

interface HandleRepository {
    suspend fun find(handle: String): HandleClaim?

    /** `saveIfNotExists`; also true when the handle is [userId]'s own, held or not. Re-activates a held handle. */
    suspend fun claim(handle: String, userId: UserId): Boolean
}

interface ContactRepository {
    suspend fun find(ownerId: UserId, contactId: UserId): Contact?

    /** Active and removed rows, [after] being the last contact id of the previous page. */
    suspend fun list(
        ownerId: UserId,
        after: UUID?,
        limit: Int,
    ): List<Contact>

    /** Who has [contactId] in their contacts (the reverse table), for fan-out. */
    suspend fun ownersOf(contactId: UserId, limit: Int): List<UserId>
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

    suspend fun blockedBy(userId: UserId): List<Block>
}

interface InviteRepository {
    suspend fun find(token: String): Invite?
}

/** Everything one operation changes, written in one batch with its outbox rows. */
data class UsersChanges(
    val profiles: List<Profile> = emptyList(),
    val deletedProfiles: List<UserId> = emptyList(),
    /** An active row writes the contact and its reverse row; a removed row writes the contact and deletes the reverse. */
    val contacts: List<Contact> = emptyList(),
    /** Hard deletes of a contact and its reverse row. */
    val deletedContacts: List<Pair<UserId, UserId>> = emptyList(),
    val blocks: List<Block> = emptyList(),
    val unblocks: List<Block> = emptyList(),
    val invites: List<Invite> = emptyList(),
    val revokedInvites: List<String> = emptyList(),
    val waiting: List<WaitingContact> = emptyList(),
    val clearedWaiting: List<WaitingContact> = emptyList(),
    /** Handles to put on a 30-day hold for their old owner. */
    val releasedHandles: List<HandleClaim> = emptyList(),
    val events: List<EventEnvelope> = emptyList(),
)

interface UsersStore {
    suspend fun commit(changes: UsersChanges)
}
