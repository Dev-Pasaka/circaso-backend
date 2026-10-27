package com.circaso.users

import com.circaso.shared.UserId
import com.circaso.shared.envelope
import com.circaso.users.api.ProfileUpdated
import com.circaso.users.data.entities.BlockRow
import com.circaso.users.data.entities.BlockedByRow
import com.circaso.users.data.entities.ContactReverseRow
import com.circaso.users.data.entities.ContactRow
import com.circaso.users.data.entities.HandleClaimRow
import com.circaso.users.data.entities.InviteRow
import com.circaso.users.data.entities.ProfileRow
import com.circaso.users.data.entities.UsersOutboxRow
import com.circaso.users.data.entities.UsersRealtimeRow
import com.circaso.users.data.entities.WaitingContactRow
import com.circaso.users.data.migrations.V001_CreateUsersTables
import com.circaso.users.data.repositories.BlockRepositoryImpl
import com.circaso.users.data.repositories.ContactRepositoryImpl
import com.circaso.users.data.repositories.HandleRepositoryImpl
import com.circaso.users.data.repositories.InviteRepositoryImpl
import com.circaso.users.data.repositories.ProfileRepositoryImpl
import com.circaso.users.data.repositories.UsersOutboxStore
import com.circaso.users.data.repositories.UsersStoreImpl
import com.circaso.users.data.repositories.WaitingContactRepositoryImpl
import com.circaso.users.domain.models.Block
import com.circaso.users.domain.models.Contact
import com.circaso.users.domain.models.ContactSource
import com.circaso.users.domain.models.ContactStatus
import com.circaso.users.domain.models.HandleClaim
import com.circaso.users.domain.models.Invite
import com.circaso.users.domain.models.Privacy
import com.circaso.users.domain.models.Profile
import com.circaso.users.domain.models.WaitingContact
import com.circaso.users.domain.repositories.UsersChanges
import io.kandra.migrate.KandraMigrationRunner
import io.kandra.test.KandraRuntimeHandle
import io.kandra.test.KandraTestBackend
import io.kandra.test.KandraTestUtils
import io.kandra.test.KandraTestcontainers
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The data layer against a real Cassandra-compatible container: the migration, version-checked updates, claims, both-way tables. */
class DataLayerTest {
    private val db: KandraRuntimeHandle =
        run {
            KandraTestBackend.newSession().use { admin ->
                admin.execute(KandraTestBackend.createKeyspaceCql("circaso_users"))
                KandraMigrationRunner(admin, keyspace = "circaso_users").run(V001_CreateUsersTables)
            }
            KandraTestcontainers.freshKeyspace(
                ProfileRow::class,
                HandleClaimRow::class,
                ContactRow::class,
                ContactReverseRow::class,
                WaitingContactRow::class,
                BlockRow::class,
                BlockedByRow::class,
                InviteRow::class,
                UsersRealtimeRow::class,
                UsersOutboxRow::class,
            )
        }

    private val bus = MemoryBus()
    private val profiles = ProfileRepositoryImpl(db.suspendRepository())
    private val handles = HandleRepositoryImpl(db.suspendRepository())
    private val contacts = ContactRepositoryImpl(db.suspendRepository(), db.suspendRepository())
    private val waiting = WaitingContactRepositoryImpl(db.suspendRepository())
    private val blocks = BlockRepositoryImpl(db.suspendRepository(), db.suspendRepository())
    private val invites = InviteRepositoryImpl(db.suspendRepository())

    // Two minutes ahead, so the event's minute-bucket has ended and the bucket-less scan reads it.
    private val outbox =
        UsersOutboxStore(db.suspendRepository(), Clock.offset(Clock.systemUTC(), Duration.ofMinutes(2)))
    private val store =
        UsersStoreImpl(
            db.runtime,
            db.suspendRepository(),
            db.suspendRepository(),
            db.suspendRepository(),
            db.suspendRepository(),
            db.suspendRepository(),
            db.suspendRepository(),
            db.suspendRepository(),
            db.suspendRepository(),
            db.suspendRepository(),
            bus,
        )

    @AfterTest
    fun close() {
        db.close()
        KandraTestUtils.reset()
    }

    private val now = Instant.now()

    private fun profile(id: UserId, name: String = "Amina") = Profile(id, name, null, null, null, null, Privacy(), now)

    private fun contact(
        owner: UserId,
        other: UserId,
        status: ContactStatus = ContactStatus.ACTIVE,
    ) = Contact(owner, other, ContactSource.PHONEBOOK, status, null, now)

    @Test
    fun `a profile saves, reads back with its privacy, and updates only from the version it was read at`() =
        runBlocking<Unit> {
            val id = UserId.new()
            store.commit(UsersChanges(profiles = listOf(profile(id))))

            val read = profiles.find(id)!!
            assertEquals("Amina", read.displayName)
            assertEquals(Privacy(), read.privacy)

            val updated = profiles.update(read, read.copy(displayName = "Amina O."))!!
            assertEquals("Amina O.", profiles.find(id)?.displayName)
            assertTrue(updated.version > read.version)

            assertNull(profiles.update(read, read.copy(displayName = "Stale")), "a stale version is refused")
            assertEquals("Amina O.", profiles.find(id)?.displayName)
            assertEquals(setOf(id), profiles.findAll(setOf(id, UserId.new())).keys)
        }

    @Test
    fun `an active contact writes both tables, a removed one drops the reverse row`() =
        runBlocking<Unit> {
            val me = UserId.new()
            val her = UserId.new()
            store.commit(UsersChanges(contacts = listOf(contact(me, her))))
            assertEquals(ContactStatus.ACTIVE, contacts.find(me, her)?.status)
            assertEquals(listOf(me), contacts.ownersOf(her, 10))

            store.commit(UsersChanges(contacts = listOf(contact(me, her, ContactStatus.REMOVED).copy(removedAt = now))))
            assertEquals(
                ContactStatus.REMOVED,
                contacts.find(me, her)?.status,
                "the row stays so a sync can't add her back",
            )
            assertTrue(contacts.ownersOf(her, 10).isEmpty())

            store.commit(UsersChanges(deletedContacts = listOf(me to her)))
            assertNull(contacts.find(me, her))
        }

    @Test
    fun `contacts page in order after a cursor`() =
        runBlocking<Unit> {
            val me = UserId.new()
            val others = (1..5).map { UserId.new() }
            store.commit(UsersChanges(contacts = others.map { contact(me, it) }))

            val first = contacts.list(me, null, 3)
            assertEquals(3, first.size)
            val rest = contacts.list(me, first.last().contactId.value, 10)
            assertEquals(2, rest.size)
            assertEquals(others.toSet(), (first + rest).map { it.contactId }.toSet())
        }

    @Test
    fun `a handle goes to one owner, then is held for its old owner after release`() =
        runBlocking<Unit> {
            val first = UserId.new()
            val second = UserId.new()
            val handle = "h_${UUID.randomUUID().toString().take(8).replace("-", "")}"

            assertTrue(handles.claim(handle, first))
            assertFalse(handles.claim(handle, second))

            store.commit(UsersChanges(releasedHandles = listOf(HandleClaim(handle, first, now, now))))
            assertNotNull(handles.find(handle)?.releasedAt)
            assertFalse(handles.claim(handle, second), "held for its old owner")
            assertTrue(handles.claim(handle, first), "the old owner takes it back")
            assertNull(handles.find(handle)?.releasedAt)
        }

    @Test
    fun `waiting contacts are found by hash and cleared`() =
        runBlocking<Unit> {
            val owner = UserId.new()
            val hmac = "hmac-${UUID.randomUUID()}"
            val row = WaitingContact(hmac, owner, now)
            store.commit(UsersChanges(waiting = listOf(row)))
            assertEquals(listOf(owner), waiting.waitingFor(hmac).map { it.ownerId })

            store.commit(UsersChanges(clearedWaiting = listOf(row)))
            assertTrue(waiting.waitingFor(hmac).isEmpty())
        }

    @Test
    fun `blocks and invites round trip`() =
        runBlocking<Unit> {
            val me = UserId.new()
            val pest = UserId.new()
            store.commit(
                UsersChanges(
                    blocks = listOf(Block(me, pest, now)),
                    invites = listOf(Invite("inv_t${UUID.randomUUID()}", me, "LINK", now)),
                ),
            )

            assertTrue(blocks.blocked(me, pest))
            assertTrue(blocks.eitherWay(pest, me))
            assertEquals(listOf(pest), blocks.list(me).map { it.blockedId })
            assertEquals(listOf(me), blocks.blockedBy(pest).map { it.userId })

            store.commit(UsersChanges(unblocks = listOf(Block(me, pest, now))))
            assertFalse(blocks.eitherWay(me, pest))
            assertNull(invites.find("inv_missing"))
        }

    @Test
    fun `an event is written with the change, published, and waits in the outbox for its stamp`() =
        runBlocking<Unit> {
            val id = UserId.new()
            val event = bus.envelope(ProfileUpdated(id, 2, listOf(ProfileUpdated.ABOUT)))

            store.commit(UsersChanges(profiles = listOf(profile(id)), events = listOf(event)))

            assertTrue(event.eventId in outbox.unpublished().map { it.eventId })
            outbox.markPublished(event, Instant.now())
            assertTrue(event.eventId !in outbox.unpublished().map { it.eventId })
        }

    @Test
    fun `deleting a long contact list goes in chunks`() =
        runBlocking<Unit> {
            val me = UserId.new()
            val others = (1..90).map { UserId.new() }
            store.commit(UsersChanges(contacts = others.map { contact(me, it) }))
            assertEquals(90, contacts.list(me, null, 200).size)

            store.commit(UsersChanges(deletedContacts = others.map { me to it }))
            assertTrue(contacts.list(me, null, 200).isEmpty())
        }

    @Test
    fun `clearing the about line, the avatar and a removal date really clears them`() =
        runBlocking<Unit> {
            val id = UserId.new()
            store.commit(
                UsersChanges(profiles = listOf(profile(id).copy(about = "Treasurer", avatarKey = "avatars/x.jpg"))),
            )
            val read = profiles.find(id)!!
            assertEquals("Treasurer", read.about)

            profiles.update(read, read.copy(about = null, avatarKey = null))!!
            assertNull(profiles.find(id)?.about)
            assertNull(profiles.find(id)?.avatarKey)

            val other = UserId.new()
            store.commit(
                UsersChanges(contacts = listOf(contact(id, other, ContactStatus.REMOVED).copy(removedAt = now))),
            )
            assertNotNull(contacts.find(id, other)?.removedAt)
            store.commit(UsersChanges(contacts = listOf(contact(id, other))))
            assertNull(contacts.find(id, other)?.removedAt, "restoring a contact clears when it was removed")
        }
}
