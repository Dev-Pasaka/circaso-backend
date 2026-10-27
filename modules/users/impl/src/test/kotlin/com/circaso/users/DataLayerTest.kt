package com.circaso.users

import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.shared.envelope
import com.circaso.users.api.ProfileUpdated
import com.circaso.users.data.repositories.BlockRepositoryImpl
import com.circaso.users.data.repositories.ContactRepositoryImpl
import com.circaso.users.data.repositories.DatabaseTransactor
import com.circaso.users.data.repositories.HandleRepositoryImpl
import com.circaso.users.data.repositories.InviteRepositoryImpl
import com.circaso.users.data.repositories.ProfileRepositoryImpl
import com.circaso.users.data.repositories.UsersExpiry
import com.circaso.users.data.repositories.UsersStoreImpl
import com.circaso.users.data.repositories.WaitingContactRepositoryImpl
import com.circaso.users.di.usersDatabaseSpec
import com.circaso.users.domain.errors.UsersError
import com.circaso.users.domain.models.Block
import com.circaso.users.domain.models.ConnectVia
import com.circaso.users.domain.models.Contact
import com.circaso.users.domain.models.ContactSource
import com.circaso.users.domain.models.ContactStatus
import com.circaso.users.domain.models.HandleClaim
import com.circaso.users.domain.models.Invite
import com.circaso.users.domain.models.Privacy
import com.circaso.users.domain.models.Profile
import com.circaso.users.domain.models.WaitingContact
import com.circaso.users.domain.repositories.UsersChanges
import com.circaso.users.domain.support.ProfileViews
import com.circaso.users.domain.support.UserNotifier
import com.circaso.users.domain.usecases.BlockUser
import com.circaso.users.domain.usecases.Connect
import com.circaso.users.domain.usecases.UnblockUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Users' data layer on a real PostgreSQL (infrastructure's test container), migrated by users' own Flyway scripts:
 * every repository method, the races the constraints settle, the purge and the outbox's atomicity.
 */
class DataLayerTest {
    private val database: Database = TestPostgres.database(usersDatabaseSpec)
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    /** Reads as if [days] had passed, to see what has expired. */
    private fun later(days: Long): Clock = Clock.offset(clock, Duration.ofDays(days))

    private val bus = MemoryBus()
    private val transactor = DatabaseTransactor(database)
    private val profiles = ProfileRepositoryImpl(database, clock)
    private val handles = HandleRepositoryImpl(database, clock)
    private val contacts = ContactRepositoryImpl(database)
    private val waiting = WaitingContactRepositoryImpl(database, clock)
    private val blocks = BlockRepositoryImpl(database)
    private val invites = InviteRepositoryImpl(database, clock)
    private val store = UsersStoreImpl(database)

    // The use cases whose races only the database settles, wired to the real repositories.
    private val reachCache = MemoryReachCache()
    private val views = ProfileViews(FakeAvatars(), contacts)
    private val notifier = UserNotifier(RecordingFrames(), contacts)
    private val connect =
        Connect(
            handles,
            invites,
            profiles,
            contacts,
            blocks,
            transactor,
            store,
            bus,
            reachCache,
            MemoryUsersCache(),
            views,
            notifier,
            clock,
        )
    private val blockUser = BlockUser(blocks, transactor, store, bus, reachCache, clock)
    private val unblockUser = UnblockUser(blocks, transactor, store, bus, reachCache)

    private fun <T> Resource<T>.data(): T = (this as Resource.Success).data

    private fun outboxRows(type: String) = count("SELECT count(*) FROM users.outbox WHERE type = ?", type)

    @BeforeTest
    fun clean() = TestPostgres.truncate("users")

    private fun profile(id: UserId, name: String = "Amina") = Profile(id, name, null, null, null, null, Privacy(), now)

    private fun contact(
        owner: UserId,
        other: UserId,
        status: ContactStatus = ContactStatus.ACTIVE,
        source: ContactSource = ContactSource.PHONEBOOK,
    ) = Contact(owner, other, source, status, if (status == ContactStatus.REMOVED) now else null, now)

    private suspend fun <T> concurrently(times: Int, block: suspend (Int) -> T): List<T> =
        coroutineScope { (0 until times).map { async(Dispatchers.Default) { block(it) } }.awaitAll() }

    private fun count(sql: String, vararg values: Any): Int =
        TestPostgres.superuser { connection ->
            connection.prepareStatement(sql).use { statement ->
                values.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    // ---- profiles ----

    @Test
    fun `a profile is created once, reads back with its privacy, and updates only from the version it was read at`() =
        runBlocking<Unit> {
            val id = UserId.new()
            assertTrue(profiles.create(profile(id)))
            assertFalse(profiles.create(profile(id, "Someone else")), "a second create keeps the first")

            val read = profiles.find(id)!!
            assertEquals("Amina", read.displayName)
            assertEquals(Privacy(), read.privacy)
            assertEquals(0, read.version)

            val updated = profiles.update(read, read.copy(displayName = "Amina O."))!!
            assertEquals(1, updated.version)
            assertEquals("Amina O.", profiles.find(id)?.displayName)

            assertNull(profiles.update(read, read.copy(displayName = "Stale")), "a stale version is refused")
            assertEquals("Amina O.", profiles.find(id)?.displayName)
            assertEquals(setOf(id), profiles.findAll(setOf(id, UserId.new())).keys)
            assertTrue(profiles.findAll(emptySet()).isEmpty())
            assertNull(profiles.find(UserId.new()))
        }

    @Test
    fun `two first reads create one profile`() =
        runBlocking<Unit> {
            val id = UserId.new()
            val created = concurrently(8) { profiles.create(profile(id, "Reader $it")) }
            assertEquals(1, created.count { it })
        }

    @Test
    fun `of two updates from the same version exactly one wins`() =
        runBlocking<Unit> {
            val id = UserId.new()
            profiles.create(profile(id))
            val read = profiles.find(id)!!

            val saved = concurrently(6) { profiles.update(read, read.copy(displayName = "Name $it")) }

            assertEquals(1, saved.count { it != null }, "the others see users.version_conflict")
            assertEquals(1, profiles.find(id)?.version)
        }

    @Test
    fun `clearing the about line and the avatar stores real nulls`() =
        runBlocking<Unit> {
            val id = UserId.new()
            profiles.create(profile(id).copy(about = "Treasurer", avatarKey = "avatars/x.jpg"))
            val read = profiles.find(id)!!
            assertEquals("Treasurer", read.about)

            profiles.update(read, read.copy(about = null, avatarKey = null))!!
            assertEquals(
                1,
                count(
                    "SELECT count(*) FROM users.profiles WHERE about IS NULL AND avatar_key IS NULL AND user_id = ?",
                    id.value,
                ),
            )
        }

    // ---- handles ----

    @Test
    fun `of many claims of one handle exactly one wins`() =
        runBlocking<Unit> {
            val people = (1..8).map { UserId.new() }
            val won = concurrently(people.size) { handles.claim("amina", people[it], now) }

            assertEquals(1, won.count { it })
            assertEquals(people[won.indexOf(true)], handles.find("amina")?.userId)
        }

    @Test
    fun `a released handle is held for its old owner, then free once the hold runs out`() =
        runBlocking<Unit> {
            val amina = UserId.new()
            val brian = UserId.new()
            assertTrue(handles.claim("amina", amina, now))
            assertTrue(handles.claim("amina", amina, now), "claiming your own handle again is fine")
            assertFalse(handles.claim("amina", brian, now))

            store.commit(UsersChanges(releasedHandles = listOf(HandleClaim("amina", amina, now, now))))
            assertNotNull(handles.find("amina")?.releasedAt)
            assertFalse(handles.claim("amina", brian, now), "on hold for its old owner")

            val afterHold = HandleRepositoryImpl(database, later(31))
            assertNull(afterHold.find("amina"), "an expired hold is invisible")
            assertTrue(afterHold.claim("amina", brian, now.plus(Duration.ofDays(31))), "and anyone may take it")
            assertEquals(brian, afterHold.find("amina")?.userId)
            assertNull(afterHold.find("amina")?.releasedAt)
        }

    @Test
    fun `the old owner takes a held handle back`() =
        runBlocking<Unit> {
            val amina = UserId.new()
            handles.claim("amina", amina, now)
            store.commit(UsersChanges(releasedHandles = listOf(HandleClaim("amina", amina, now, now))))

            assertTrue(handles.claim("amina", amina, now))
            assertNull(handles.find("amina")?.releasedAt)
            assertEquals(0, count("SELECT count(*) FROM users.handles WHERE expires_at IS NOT NULL"))
        }

    @Test
    fun `a release never touches a handle someone else holds`() =
        runBlocking<Unit> {
            val amina = UserId.new()
            handles.claim("amina", amina, now)
            store.commit(UsersChanges(releasedHandles = listOf(HandleClaim("amina", UserId.new(), now, now))))
            assertNull(handles.find("amina")?.releasedAt)
        }

    @Test
    fun `a failed step after the claim rolls the claim back`() =
        runBlocking<Unit> {
            val amina = UserId.new()

            val answer =
                transactor.attempt("handles.claim") {
                    handles.claim("amina", amina, now)
                    Resource.error(UsersError.VersionConflict)
                }

            assertEquals(UsersError.VersionConflict, (answer as Resource.Error).error)
            assertNull(handles.find("amina"))
        }

    // ---- contacts ----

    @Test
    fun `a contact added twice keeps its first row, and a sync never re-adds a removed one`() =
        runBlocking<Unit> {
            val me = UserId.new()
            val her = UserId.new()
            store.commit(UsersChanges(contacts = listOf(contact(me, her))))
            assertEquals(ContactStatus.ACTIVE, contacts.find(me, her)?.status)
            assertEquals(listOf(me), contacts.ownersOf(her, 10))

            assertTrue(contacts.remove(me, her, now))
            assertFalse(contacts.remove(me, her, now), "nothing active left to remove")
            assertEquals(ContactStatus.REMOVED, contacts.find(me, her)?.status)
            assertEquals(now, contacts.find(me, her)?.removedAt)
            assertTrue(contacts.ownersOf(her, 10).isEmpty(), "who-has-me sees active rows only")

            store.commit(UsersChanges(contacts = listOf(contact(me, her))))
            assertEquals(ContactStatus.REMOVED, contacts.find(me, her)?.status, "the sync's insert is ignored")

            assertTrue(contacts.connect(contact(me, her, source = ContactSource.QR).copy(addedAt = now.plusSeconds(9))))
            val restored = contacts.find(me, her)!!
            assertEquals(ContactStatus.ACTIVE, restored.status, "a connection restores it")
            assertEquals(ContactSource.QR, restored.source)
            assertNull(restored.removedAt)
            assertEquals(now, restored.addedAt, "it keeps when it was first added")
            assertFalse(contacts.connect(contact(me, her, source = ContactSource.HANDLE)), "already active: no change")
            assertEquals(ContactSource.QR, contacts.find(me, her)?.source)
        }

    @Test
    fun `a pair is two rows, one per direction, each read from both sides`() =
        runBlocking<Unit> {
            val amina = UserId.new()
            val brian = UserId.new()
            assertTrue(contacts.connect(contact(amina, brian)))
            assertTrue(contacts.connect(contact(brian, amina)))

            assertEquals(listOf(brian), contacts.ownersOf(amina, 10))
            assertEquals(listOf(amina), contacts.ownersOf(brian, 10))
            assertTrue(contacts.remove(amina, brian, now))
            assertEquals(ContactStatus.ACTIVE, contacts.find(brian, amina)?.status, "the other side keeps theirs")
        }

    @Test
    fun `of two removals of one contact exactly one succeeds`() =
        runBlocking<Unit> {
            val me = UserId.new()
            val her = UserId.new()
            store.commit(UsersChanges(contacts = listOf(contact(me, her))))

            val removed = concurrently(6) { contacts.remove(me, her, now) }

            assertEquals(1, removed.count { it })
        }

    @Test
    fun `contacts page by keyset, in contact-id order, removed ones only when asked`() =
        runBlocking<Unit> {
            val me = UserId.new()
            val others = (1..5).map { UserId.new() }.sortedBy { it.value }
            store.commit(UsersChanges(contacts = others.map { contact(me, it) }))
            contacts.remove(me, others[1], now)

            val first = contacts.list(me, null, 2, activeOnly = true)
            assertEquals(listOf(others[0], others[2]), first.map { it.contactId })
            val rest = contacts.list(me, first.last().contactId.value, 10, activeOnly = true)
            assertEquals(listOf(others[3], others[4]), rest.map { it.contactId })
            assertEquals(5, contacts.list(me, null, 10, activeOnly = false).size)

            val found = contacts.findAll(me, setOf(others[0], others[1], UserId.new()))
            assertEquals(setOf(others[0], others[1]), found.keys)
            assertEquals(ContactStatus.REMOVED, found[others[1]]?.status)
            assertTrue(contacts.findAll(me, emptySet()).isEmpty())
        }

    @Test
    fun `of many connects of one pair exactly one creates the contact and writes the event`() =
        runBlocking<Unit> {
            val owner = UserId.new()
            val guest = UserId.new()
            profiles.create(profile(owner))
            profiles.create(profile(guest, "Guest"))
            store.commit(UsersChanges(invites = listOf(Invite("inv_pair", owner, "LINK", now, now.plusSeconds(600)))))

            val created = concurrently(8) { connect(guest, ConnectVia.QR, "inv_pair").data().created }

            assertEquals(1, created.count { it })
            assertEquals(1, outboxRows("users.ConnectionAdded"))
            assertEquals(ContactStatus.ACTIVE, contacts.find(guest, owner)?.status)
            assertEquals(ContactStatus.ACTIVE, contacts.find(owner, guest)?.status)
        }

    @Test
    fun `two people connecting to each other at once both succeed`() =
        runBlocking<Unit> {
            repeat(10) { round ->
                val amina = UserId.new()
                val brian = UserId.new()
                profiles.create(profile(amina))
                profiles.create(profile(brian, "Brian"))
                val invitesOf = listOf(Invite("inv_a$round", amina, "LINK", now, now.plusSeconds(600)))
                store.commit(
                    UsersChanges(invites = invitesOf + Invite("inv_b$round", brian, "LINK", now, now.plusSeconds(600))),
                )

                val answers =
                    coroutineScope {
                        listOf(
                            async(Dispatchers.Default) { connect(amina, ConnectVia.QR, "inv_b$round") },
                            async(Dispatchers.Default) { connect(brian, ConnectVia.QR, "inv_a$round") },
                        ).awaitAll()
                    }

                assertTrue(answers.all { it is Resource.Success }, "round $round: $answers")
                assertEquals(ContactStatus.ACTIVE, contacts.find(amina, brian)?.status)
                assertEquals(ContactStatus.ACTIVE, contacts.find(brian, amina)?.status)
            }
        }

    @Test
    fun `a connect that finds a block or no profile writes nothing`() =
        runBlocking<Unit> {
            val owner = UserId.new()
            val guest = UserId.new()
            profiles.create(profile(guest, "Guest"))
            store.commit(UsersChanges(invites = listOf(Invite("inv_gone", owner, "LINK", now, now.plusSeconds(600)))))

            assertEquals(SharedError.NotFound, (connect(guest, ConnectVia.QR, "inv_gone") as Resource.Error).error)
            profiles.create(profile(owner))
            blocks.add(Block(owner, guest, now))
            assertEquals(UsersError.NotReachable, (connect(guest, ConnectVia.QR, "inv_gone") as Resource.Error).error)

            assertNull(contacts.find(guest, owner))
            assertEquals(0, outboxRows("users.ConnectionAdded"))
        }

    // ---- waiting contacts ----

    @Test
    fun `waiting contacts are found by hash, renewed by a later sync, cleared, and expire after 180 days`() =
        runBlocking<Unit> {
            val owner = UserId.new()
            val row = WaitingContact("hmac-1", owner, now)
            store.commit(UsersChanges(waiting = listOf(row)))
            store.commit(UsersChanges(waiting = listOf(row.copy(addedAt = now.plusSeconds(60)))))
            assertEquals(listOf(owner), waiting.waitingFor("hmac-1").map { it.ownerId }, "one row, renewed")
            assertTrue(WaitingContactRepositoryImpl(database, later(181)).waitingFor("hmac-1").isEmpty())

            store.commit(UsersChanges(clearedWaiting = listOf(row)))
            assertTrue(waiting.waitingFor("hmac-1").isEmpty())
        }

    // ---- blocks ----

    @Test
    fun `a block reads from both sides and a second block of the same pair changes nothing`() =
        runBlocking<Unit> {
            val me = UserId.new()
            val pest = UserId.new()
            assertEquals(1, concurrently(6) { blocks.add(Block(me, pest, now)) }.count { it })

            assertTrue(blocks.blocked(me, pest))
            assertFalse(blocks.blocked(pest, me))
            assertTrue(blocks.eitherWay(me, pest))
            assertTrue(blocks.eitherWay(pest, me))
            assertEquals(listOf(pest), blocks.list(me).map { it.blockedId })
            assertTrue(blocks.list(pest).isEmpty())

            assertEquals(1, concurrently(6) { blocks.remove(me, pest) }.count { it })
            assertFalse(blocks.eitherWay(me, pest))
        }

    @Test
    fun `of many blocks and unblocks of one pair exactly one each writes its event`() =
        runBlocking<Unit> {
            val me = UserId.new()
            val pest = UserId.new()

            concurrently(8) { blockUser(me, pest) }
            assertEquals(1, outboxRows("users.UserBlocked"))
            concurrently(8) { unblockUser(me, pest) }
            assertEquals(1, outboxRows("users.UserUnblocked"))
            assertFalse(blocks.eitherWay(me, pest))
        }

    // ---- invites ----

    @Test
    fun `an invite is found until it is revoked or expires`() =
        runBlocking<Unit> {
            val me = UserId.new()
            val invite = Invite("inv_one", me, "LINK", now, now.plus(Duration.ofDays(7)))
            store.commit(UsersChanges(invites = listOf(invite)))

            assertEquals(invite, invites.find("inv_one"))
            assertNull(InviteRepositoryImpl(database, later(8)).find("inv_one"), "expired")
            assertNull(invites.find("inv_missing"))

            assertFalse(invites.revoke("inv_one", UserId.new()), "only its owner can revoke it")
            assertFalse(InviteRepositoryImpl(database, later(8)).revoke("inv_one", me), "an expired one is gone")
            assertTrue(invites.revoke("inv_one", me))
            assertFalse(invites.revoke("inv_one", me))
            assertNull(invites.find("inv_one"))
        }

    @Test
    fun `an invite redeemed by two people at once connects each of them both ways`() =
        runBlocking<Unit> {
            val owner = UserId.new()
            val people = (1..4).map { UserId.new() }
            store.commit(UsersChanges(invites = listOf(Invite("inv_qr", owner, "LINK", now, now.plusSeconds(600)))))

            profiles.create(profile(owner))
            people.forEach { profiles.create(profile(it, "Guest")) }

            val created = concurrently(people.size) { connect(people[it], ConnectVia.QR, "inv_qr").data().created }

            assertTrue(created.all { it }, "each person connects once")

            assertEquals(people.toSet(), contacts.list(owner, null, 10, activeOnly = true).map { it.contactId }.toSet())
            people.forEach { assertEquals(ContactStatus.ACTIVE, contacts.find(it, owner)?.status) }
            assertNotNull(invites.find("inv_qr"), "an invite stays usable until it expires or is revoked")
        }

    // ---- account deletion ----

    @Test
    fun `erasing a user removes the profile and every contact and block either way`() =
        runBlocking<Unit> {
            val gone = UserId.new()
            val other = UserId.new()
            profiles.create(profile(gone))
            profiles.create(profile(other, "Other"))
            contacts.connect(contact(gone, other))
            contacts.connect(contact(other, gone))
            contacts.remove(other, gone, now)
            blocks.add(Block(gone, UserId.new(), now))
            blocks.add(Block(UserId.new(), gone, now))

            store.commit(UsersChanges(erasedUsers = listOf(gone)))

            assertNull(profiles.find(gone))
            assertNotNull(profiles.find(other))
            assertEquals(
                0,
                count(
                    "SELECT count(*) FROM users.contacts WHERE owner_id = ? OR contact_id = ?",
                    gone.value,
                    gone.value,
                ),
            )
            assertEquals(
                0,
                count("SELECT count(*) FROM users.blocks WHERE user_id = ? OR blocked_id = ?", gone.value, gone.value),
            )
        }

    // ---- outbox ----

    @Test
    fun `an event lands with the change, and neither lands when the transaction rolls back`() =
        runBlocking<Unit> {
            val me = UserId.new()
            val her = UserId.new()
            val kept = bus.envelope(ProfileUpdated(me, 1, listOf(ProfileUpdated.ABOUT)))
            val lost = bus.envelope(ProfileUpdated(her, 1, listOf(ProfileUpdated.ABOUT)))

            store.commit(UsersChanges(contacts = listOf(contact(me, her)), events = listOf(kept)))
            transactor.attempt("contacts.sync") {
                store.commit(UsersChanges(contacts = listOf(contact(her, me)), events = listOf(lost)))
                Resource.error(UsersError.VersionConflict)
            }

            val outbox = "SELECT count(*) FROM users.outbox WHERE event_id = ? AND published_at IS NULL"
            assertEquals(1, count(outbox, kept.eventId.value))
            assertNotNull(contacts.find(me, her))
            assertEquals(0, count(outbox, lost.eventId.value))
            assertNull(contacts.find(her, me))
        }

    // ---- expiry ----

    @Test
    fun `the purge deletes expired rows and keeps the partitions a day ahead`() =
        runBlocking<Unit> {
            val owner = UserId.new()
            val past = Instant.now().minus(Duration.ofDays(200))
            handles.claim("old_one", owner, past)
            handles.claim("held_one", owner, past)
            store.commit(
                UsersChanges(
                    releasedHandles =
                        listOf(HandleClaim("old_one", owner, past, past), HandleClaim("held_one", owner, past, now)),
                    waiting = listOf(WaitingContact("old", owner, past), WaitingContact("fresh", owner, now)),
                    invites =
                        listOf(
                            Invite("inv_old", owner, "LINK", past, past.plus(Duration.ofDays(7))),
                            Invite("inv_new", owner, "LINK", now, now.plus(Duration.ofDays(7))),
                        ),
                ),
            )

            val touched = UsersExpiry(database).run()

            assertTrue(touched >= 3, "three expired rows, plus any partition it had to make ($touched)")
            assertEquals(1, count("SELECT count(*) FROM users.handles"), "the live hold stays")
            assertEquals(listOf(owner), waiting.waitingFor("fresh").map { it.ownerId })
            assertEquals(1, count("SELECT count(*) FROM users.waiting_contacts"))
            assertEquals(1, count("SELECT count(*) FROM users.invites"))
            val tomorrow =
                now
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate()
                    .plusDays(1)
                    .toString()
                    .replace("-", "")
            for (table in listOf("realtime", "outbox")) {
                assertEquals(
                    1,
                    count(
                        "SELECT count(*) FROM pg_tables WHERE schemaname = 'users' AND tablename = ?",
                        "${table}_p$tomorrow",
                    ),
                    "$table has tomorrow's partition",
                )
            }
        }

    @Test
    fun `a claim racing the purge of an expired hold always wins`() =
        runBlocking<Unit> {
            val past = Instant.now().minus(Duration.ofDays(60))
            repeat(25) { round ->
                val handle = "race_$round"
                val old = UserId.new()
                handles.claim(handle, old, past)
                store.commit(UsersChanges(releasedHandles = listOf(HandleClaim(handle, old, past, past))))

                val newOwner = UserId.new()
                val claimed =
                    coroutineScope {
                        val purge = async(Dispatchers.Default) { UsersExpiry(database).run() }
                        val claim = async(Dispatchers.Default) { handles.claim(handle, newOwner, now) }
                        purge.await()
                        claim.await()
                    }

                assertTrue(claimed, "round $round")
                assertEquals(newOwner, handles.find(handle)?.userId)
            }
        }

    @Test
    fun `handles claimed after the purge has emptied an expired hold still work`() =
        runBlocking<Unit> {
            val amina = UserId.new()
            val past = Instant.now().minus(Duration.ofDays(60))
            handles.claim("amina", amina, past)
            store.commit(UsersChanges(releasedHandles = listOf(HandleClaim("amina", amina, past, past))))
            UsersExpiry(database).run()

            assertTrue(handles.claim("amina", UserId.new(), now))
        }
}
