package com.circaso.users.data.repositories

import com.circaso.infrastructure.api.OutboxStore
import com.circaso.shared.EventBus
import com.circaso.shared.EventEnvelope
import com.circaso.shared.UserId
import com.circaso.users.data.entities.BlockRow
import com.circaso.users.data.entities.BlockedByRow
import com.circaso.users.data.entities.ContactReverseRow
import com.circaso.users.data.entities.ContactRow
import com.circaso.users.data.entities.HandleClaimRow
import com.circaso.users.data.entities.InviteRow
import com.circaso.users.data.entities.ProfileRow
import com.circaso.users.data.entities.UsersOutboxRow
import com.circaso.users.data.entities.WaitingContactRow
import com.circaso.users.data.mappers.toModel
import com.circaso.users.data.mappers.toRow
import com.circaso.users.domain.models.Block
import com.circaso.users.domain.models.Contact
import com.circaso.users.domain.models.ContactStatus
import com.circaso.users.domain.models.HandleClaim
import com.circaso.users.domain.models.Invite
import com.circaso.users.domain.models.Profile
import com.circaso.users.domain.models.WaitingContact
import com.circaso.users.domain.repositories.BlockRepository
import com.circaso.users.domain.repositories.ContactRepository
import com.circaso.users.domain.repositories.HandleRepository
import com.circaso.users.domain.repositories.InviteRepository
import com.circaso.users.domain.repositories.ProfileRepository
import com.circaso.users.domain.repositories.UsersChanges
import com.circaso.users.domain.repositories.UsersStore
import com.circaso.users.domain.repositories.WaitingContactRepository
import io.kandra.core.ExperimentalKandraApi
import io.kandra.core.exception.KandraOptimisticLockException
import io.kandra.runtime.KandraRuntime
import io.kandra.runtime.dsl.KandraColumnRef
import io.kandra.runtime.repository.KandraSuspendRepository
import java.time.Instant
import java.util.UUID

// Column names Kandra derives from the entity properties (camelCase to snake_case).
private val USER_ID = KandraColumnRef<UUID>("user_id")
private val OWNER_ID = KandraColumnRef<UUID>("owner_id")
private val CONTACT_ID = KandraColumnRef<UUID>("contact_id")
private val BLOCKED_ID = KandraColumnRef<UUID>("blocked_id")
private val PHONE_HMAC = KandraColumnRef<String>("phone_hmac")
private val BUCKET = KandraColumnRef<String>("bucket")

private const val HANDLE_HOLD_SECONDS = 60 * 60 * 24 * 30

class ProfileRepositoryImpl(private val profiles: KandraSuspendRepository<ProfileRow>) : ProfileRepository {
    override suspend fun find(userId: UserId): Profile? = profiles.findById(userId.value)?.toModel()

    override suspend fun findAll(ids: Set<UserId>): Map<UserId, Profile> {
        if (ids.isEmpty()) return emptyMap()
        return profiles.findAll { USER_ID isIn ids.map { it.value } }.map { it.toModel() }.associateBy { it.userId }
    }

    override suspend fun update(old: Profile, new: Profile): Profile? = try {
        profiles.updateAndGet(old.toRow(), new.toRow()).toModel()
    } catch (e: KandraOptimisticLockException) {
        null
    }
}

class HandleRepositoryImpl(private val claims: KandraSuspendRepository<HandleClaimRow>) : HandleRepository {
    override suspend fun find(handle: String): HandleClaim? = claims.findById(handle)?.toModel()

    override suspend fun claim(handle: String, userId: UserId): Boolean {
        if (claims.saveIfNotExists(HandleClaimRow(handle, userId.value, Instant.now()))) return true
        val existing = claims.findById(handle) ?: return false
        if (existing.userId != userId.value) return false
        // Its own handle, on hold: take it back. A plain save of null leaves the old value (it is "unset"), so write the null.
        if (existing.releasedAt != null) claims.saveWithNulls(existing.copy(releasedAt = null))
        return true
    }
}

class ContactRepositoryImpl(
    private val contacts: KandraSuspendRepository<ContactRow>,
    private val reverse: KandraSuspendRepository<ContactReverseRow>,
) : ContactRepository {
    override suspend fun find(ownerId: UserId, contactId: UserId): Contact? = contacts.findById(ownerId.value, contactId.value)?.toModel()

    override suspend fun list(ownerId: UserId, after: UUID?, limit: Int): List<Contact> =
        contacts.findAll(limit) {
            OWNER_ID eq ownerId.value
            if (after != null) CONTACT_ID gt after
        }.map { it.toModel() }

    override suspend fun ownersOf(contactId: UserId, limit: Int): List<UserId> =
        reverse.findAll(limit) { CONTACT_ID eq contactId.value }.map { UserId(it.ownerId) }
}

class WaitingContactRepositoryImpl(private val waiting: KandraSuspendRepository<WaitingContactRow>) : WaitingContactRepository {
    override suspend fun waitingFor(phoneHmac: String): List<WaitingContact> =
        waiting.findAll { PHONE_HMAC eq phoneHmac }.map { it.toModel() }
}

class BlockRepositoryImpl(
    private val blocks: KandraSuspendRepository<BlockRow>,
    private val blockedBy: KandraSuspendRepository<BlockedByRow>,
) : BlockRepository {
    override suspend fun blocked(userId: UserId, blockedId: UserId): Boolean = blocks.findById(userId.value, blockedId.value) != null

    override suspend fun eitherWay(a: UserId, b: UserId): Boolean = blocked(a, b) || blocked(b, a)

    override suspend fun list(userId: UserId): List<Block> = blocks.findAll { USER_ID eq userId.value }.map { it.toModel() }

    override suspend fun blockedBy(userId: UserId): List<Block> =
        blockedBy.findAll { BLOCKED_ID eq userId.value }.map { Block(UserId(it.userId), UserId(it.blockedId), it.blockedAt) }
}

class InviteRepositoryImpl(private val invites: KandraSuspendRepository<InviteRow>) : InviteRepository {
    override suspend fun find(token: String): Invite? = invites.findById(token)?.toModel()
}

/**
 * Writes one operation's rows and its outbox rows in a single LOGGED batch, then hands the events to the bus.
 * Contacts and blocks are each two tables, written together because the module reads them from both sides.
 * Very long lists (deleting a whole account) go in chunks, so no batch grows past what the database allows.
 */
@OptIn(ExperimentalKandraApi::class)
class UsersStoreImpl(
    private val runtime: KandraRuntime,
    private val profiles: KandraSuspendRepository<ProfileRow>,
    private val handles: KandraSuspendRepository<HandleClaimRow>,
    private val contacts: KandraSuspendRepository<ContactRow>,
    private val reverse: KandraSuspendRepository<ContactReverseRow>,
    private val waiting: KandraSuspendRepository<WaitingContactRow>,
    private val blocks: KandraSuspendRepository<BlockRow>,
    private val blockedBy: KandraSuspendRepository<BlockedByRow>,
    private val invites: KandraSuspendRepository<InviteRow>,
    private val outbox: KandraSuspendRepository<UsersOutboxRow>,
    private val bus: EventBus,
) : UsersStore {

    override suspend fun commit(changes: UsersChanges) {
        val chunks = changes.contacts.chunked(CHUNK).size.coerceAtLeast(changes.deletedContacts.chunked(CHUNK).size).coerceAtLeast(1)
        for (i in 0 until chunks) {
            val last = i == chunks - 1
            write(
                UsersChanges(
                    contacts = changes.contacts.chunked(CHUNK).getOrElse(i) { emptyList() },
                    deletedContacts = changes.deletedContacts.chunked(CHUNK).getOrElse(i) { emptyList() },
                    // everything else rides with the last chunk, together with the events
                    profiles = if (last) changes.profiles else emptyList(),
                    deletedProfiles = if (last) changes.deletedProfiles else emptyList(),
                    blocks = if (last) changes.blocks else emptyList(),
                    unblocks = if (last) changes.unblocks else emptyList(),
                    invites = if (last) changes.invites else emptyList(),
                    revokedInvites = if (last) changes.revokedInvites else emptyList(),
                    waiting = if (last) changes.waiting else emptyList(),
                    clearedWaiting = if (last) changes.clearedWaiting else emptyList(),
                    releasedHandles = if (last) changes.releasedHandles else emptyList(),
                    events = if (last) changes.events else emptyList(),
                ),
            )
        }
        changes.events.forEach { bus.publish(it) }
    }

    private suspend fun write(c: UsersChanges) {
        if (c == UsersChanges()) return
        runtime.batch {
            c.profiles.forEach { profiles.saveInBatch(it.toRow()) }
            c.deletedProfiles.forEach { profiles.deleteInBatch(ProfileRow(it.value, "", null, null, null, null, "{}", Instant.EPOCH)) }
            c.contacts.forEach { contact ->
                contacts.saveInBatch(contact.toRow())
                val reverseRow = ContactReverseRow(contact.contactId.value, contact.ownerId.value, contact.addedAt)
                if (contact.status == ContactStatus.ACTIVE) reverse.saveInBatch(reverseRow) else reverse.deleteInBatch(reverseRow)
            }
            c.deletedContacts.forEach { (owner, contact) ->
                contacts.deleteInBatch(ContactRow(owner.value, contact.value, "PHONEBOOK", "ACTIVE", null, Instant.EPOCH))
                reverse.deleteInBatch(ContactReverseRow(contact.value, owner.value, Instant.EPOCH))
            }
            c.blocks.forEach {
                blocks.saveInBatch(BlockRow(it.userId.value, it.blockedId.value, it.blockedAt))
                blockedBy.saveInBatch(BlockedByRow(it.blockedId.value, it.userId.value, it.blockedAt))
            }
            c.unblocks.forEach {
                blocks.deleteInBatch(BlockRow(it.userId.value, it.blockedId.value, it.blockedAt))
                blockedBy.deleteInBatch(BlockedByRow(it.blockedId.value, it.userId.value, it.blockedAt))
            }
            c.invites.forEach { invites.saveInBatch(InviteRow(it.token, it.ownerId.value, it.kind, it.createdAt)) }
            c.revokedInvites.forEach { invites.deleteInBatch(InviteRow(it, UUID(0, 0), "LINK", Instant.EPOCH)) }
            c.waiting.forEach { waiting.saveInBatch(WaitingContactRow(it.phoneHmac, it.ownerId.value, it.addedAt)) }
            c.clearedWaiting.forEach { waiting.deleteInBatch(WaitingContactRow(it.phoneHmac, it.ownerId.value, it.addedAt)) }
            c.releasedHandles.forEach {
                handles.saveInBatch(HandleClaimRow(it.handle, it.userId.value, it.claimedAt, it.releasedAt), ttlSeconds = HANDLE_HOLD_SECONDS)
            }
            c.events.forEach { outbox.saveInBatch(it.toOutboxRow()) }
        }
    }

    private companion object {
        const val CHUNK = 40
    }
}

private fun EventEnvelope.toOutboxRow() = UsersOutboxRow(bucket, eventId.value, type, toJson())

/** The relay's view of users' outbox: events written but not yet on the stream. */
class UsersOutboxStore(private val outbox: KandraSuspendRepository<UsersOutboxRow>) : OutboxStore {
    override val producer = "users"

    override suspend fun unpublished(bucket: String): List<EventEnvelope> =
        outbox.findAll { BUCKET eq bucket }.filter { it.publishedAt == null }.map { EventEnvelope.fromJson(it.payload) }

    override suspend fun markPublished(envelope: EventEnvelope, at: Instant) {
        val row = outbox.findById(envelope.bucket, envelope.eventId.value) ?: return
        outbox.save(row.copy(publishedAt = at))
    }
}
