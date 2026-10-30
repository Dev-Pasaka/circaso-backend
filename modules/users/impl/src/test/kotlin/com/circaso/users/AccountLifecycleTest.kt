package com.circaso.users

import com.circaso.identity.api.AccountDeleted
import com.circaso.identity.api.AccountStatus
import com.circaso.identity.api.AccountStatusChanged
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.users.data.repositories.BlockRepositoryImpl
import com.circaso.users.data.repositories.ContactRepositoryImpl
import com.circaso.users.data.repositories.DatabaseTransactor
import com.circaso.users.data.repositories.HandleRepositoryImpl
import com.circaso.users.data.repositories.InviteRepositoryImpl
import com.circaso.users.data.repositories.ProfileRepositoryImpl
import com.circaso.users.data.repositories.UsersStoreImpl
import com.circaso.users.di.usersDatabaseSpec
import com.circaso.users.domain.errors.UsersError
import com.circaso.users.domain.models.Block
import com.circaso.users.domain.models.ConnectVia
import com.circaso.users.domain.models.Contact
import com.circaso.users.domain.models.ContactSource
import com.circaso.users.domain.models.ContactStatus
import com.circaso.users.domain.models.Invite
import com.circaso.users.domain.repositories.UsersChanges
import com.circaso.users.domain.support.ProfileProvisioner
import com.circaso.users.domain.support.ProfileViews
import com.circaso.users.domain.support.UserNotifier
import com.circaso.users.domain.usecases.ApplyAccountStatus
import com.circaso.users.domain.usecases.BlockUser
import com.circaso.users.domain.usecases.ClaimHandle
import com.circaso.users.domain.usecases.Connect
import com.circaso.users.domain.usecases.DeleteUserData
import com.circaso.users.domain.usecases.GetProfiles
import com.circaso.users.domain.usecases.ListBlocks
import com.circaso.users.domain.usecases.ListContacts
import com.circaso.users.domain.usecases.LookupHandle
import com.circaso.users.domain.usecases.SearchContacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What other people see of an account that isn't active (found in QA): hidden while deleted in its grace period,
 * back when it is active again; deletion's invites; same-user handle races; blocks of unknown ids. On PostgreSQL.
 */
class AccountLifecycleTest {
    private val database = TestPostgres.database(usersDatabaseSpec)
    private val clock = Clock.systemUTC()
    private val bus = MemoryBus()
    private val transactor = DatabaseTransactor(database)
    private val profiles = ProfileRepositoryImpl(database, clock)
    private val handles = HandleRepositoryImpl(database, clock)
    private val contacts = ContactRepositoryImpl(database)
    private val blocks = BlockRepositoryImpl(database)
    private val invites = InviteRepositoryImpl(database, clock)
    private val store = UsersStoreImpl(database)
    private val reachCache = MemoryReachCache()
    private val usersCache = MemoryUsersCache()
    private val directory = FakeDirectory()
    private val avatars = FakeAvatars()
    private val views = ProfileViews(avatars, contacts)
    private val notifier = UserNotifier(RecordingFrames(), contacts)
    private val provisioner = ProfileProvisioner(profiles, transactor, clock)

    private val applyStatus = ApplyAccountStatus(provisioner, profiles, reachCache)
    private val claimHandle =
        ClaimHandle(provisioner, profiles, handles, transactor, store, bus, notifier, views, clock, usersCache)
    private val lookupHandle = LookupHandle(handles, profiles, blocks, views, usersCache)
    private val listContacts = ListContacts(contacts, profiles, views)
    private val searchContacts = SearchContacts(contacts, profiles, views)
    private val listBlocks = ListBlocks(blocks, profiles, views)
    private val getProfiles = GetProfiles(profiles, views)
    private val deleteUserData = DeleteUserData(profiles, avatars, transactor, store, reachCache, clock)
    private val blockUser = BlockUser(directory, blocks, transactor, store, bus, reachCache, clock)
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
            usersCache,
            views,
            notifier,
            clock,
        )

    @BeforeTest
    fun clean() = TestPostgres.truncate("users")

    private fun <T> Resource<T>.data(): T = (this as Resource.Success).data

    private fun Resource<*>.error() = (this as Resource.Error).error

    private fun outboxRows(type: String): Int =
        TestPostgres.superuser { connection ->
            connection.prepareStatement("SELECT count(*) FROM users.outbox WHERE type = ?").use { statement ->
                statement.setString(1, type)
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    private suspend fun person(name: String): UserId {
        val id = UserId.new()
        directory.statuses[id] = AccountStatus.ACTIVE
        provisioner.ensure(id, name)
        return id
    }

    private suspend fun befriend(a: UserId, b: UserId) {
        val now = Instant.now()
        contacts.connect(Contact(a, b, ContactSource.QR, ContactStatus.ACTIVE, null, now))
        contacts.connect(Contact(b, a, ContactSource.QR, ContactStatus.ACTIVE, null, now))
    }

    private suspend fun status(
        userId: UserId,
        status: AccountStatus,
        at: Instant,
    ) {
        directory.statuses[userId] = status
        applyStatus(AccountStatusChanged(userId, status), at).data()
    }

    @Test
    fun `a deleted account is found by nobody and connectable by nobody until it is restored`() =
        runBlocking<Unit> {
            val dee = person("Dee")
            val friend = person("Friend")
            val newcomer = person("Newcomer")
            claimHandle(dee, "dee").data()
            befriend(dee, friend)
            store.commit(
                UsersChanges(
                    invites = listOf(Invite("inv_dee", dee, "LINK", Instant.now(), Instant.now().plusSeconds(600))),
                ),
            )
            val deletedAt = Instant.now()

            status(dee, AccountStatus.DELETED, deletedAt)

            assertEquals(UsersError.HandleNotFound, lookupHandle(friend, "dee").error())
            assertTrue(listContacts(friend, null, 50).data().entries.none { it.profile.userId == dee })
            assertTrue(searchContacts(friend, "dee", 5).data().isEmpty())
            assertEquals(UsersError.HandleNotFound, connect(newcomer, ConnectVia.HANDLE, "dee").error())
            assertEquals(UsersError.InviteInvalid, connect(newcomer, ConnectVia.INVITE, "inv_dee").error())
            assertEquals(0, outboxRows("users.ConnectionAdded"))
            assertNull(getProfiles(setOf(dee)).data()[dee]?.handle, "other modules see the plain fallback")

            // a late copy of an older event changes nothing; the restore does
            status(dee, AccountStatus.ACTIVE, deletedAt.plusSeconds(1))
            applyStatus(AccountStatusChanged(dee, AccountStatus.DELETED), deletedAt.minusSeconds(60)).data()

            assertEquals(dee, lookupHandle(friend, "dee").data().userId)
            assertTrue(listContacts(friend, null, 50).data().entries.any { it.profile.userId == dee })
            assertTrue(connect(newcomer, ConnectVia.HANDLE, "dee").data().created)
            assertEquals(1, outboxRows("users.ConnectionAdded"))
        }

    @Test
    fun `someone who is hidden drops out of a block list`() =
        runBlocking<Unit> {
            val me = person("Me")
            val pest = person("Pest")
            blocks.add(Block(me, pest, Instant.now()))

            status(pest, AccountStatus.SUSPENDED, Instant.now())

            assertTrue(listBlocks(me).data().isEmpty())
        }

    @Test
    fun `an account deletion takes its invites, and a connect through one answers invite_invalid`() =
        runBlocking<Unit> {
            val owner = person("Owner")
            val guest = person("Guest")
            val expires = Instant.now().plus(Duration.ofDays(7))
            store.commit(UsersChanges(invites = listOf(Invite("inv_owner", owner, "LINK", Instant.now(), expires))))

            deleteUserData(AccountDeleted(owner)).data()

            assertNull(invites.find("inv_owner"))
            assertEquals(UsersError.InviteInvalid, connect(guest, ConnectVia.INVITE, "inv_owner").error())
        }

    @Test
    fun `parallel claims of one handle by the same user all answer with the handle, and write one event`() =
        runBlocking<Unit> {
            val amina = person("Amina")

            val answers =
                coroutineScope {
                    (1..5).map { async(Dispatchers.Default) { claimHandle(amina, "amina") } }.awaitAll()
                }

            assertTrue(answers.all { it is Resource.Success }, "$answers")
            assertTrue(answers.all { it.data().profile.handle == "amina" })
            assertEquals(1, outboxRows("users.ProfileUpdated"))
        }

    @Test
    fun `blocking an id that is no account answers not found and writes nothing`() =
        runBlocking<Unit> {
            val me = person("Me")

            assertEquals(SharedError.NotFound, blockUser(me, UserId.new()).error())

            assertTrue(blocks.list(me).isEmpty())
            assertEquals(0, outboxRows("users.UserBlocked"))
        }
}
