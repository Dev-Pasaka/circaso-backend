package com.circaso.users

import com.circaso.identity.api.AccountActivated
import com.circaso.identity.api.AccountDeleted
import com.circaso.identity.api.AccountStatus
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.api.PublicProfile
import com.circaso.users.domain.models.AboutVisibility
import com.circaso.users.domain.models.ConnectVia
import com.circaso.users.domain.models.Contact
import com.circaso.users.domain.models.ContactSource
import com.circaso.users.domain.models.ContactStatus
import com.circaso.users.domain.models.HandleAvailability
import com.circaso.users.domain.models.ProfileChange
import com.circaso.users.domain.models.SyncCommand
import com.circaso.users.domain.ports.LoggedFrame
import com.circaso.users.domain.ports.StoredAvatar
import com.circaso.users.domain.repositories.BlockRepository
import com.circaso.users.domain.repositories.UsersChanges
import com.circaso.users.domain.support.ProfileViews
import com.circaso.users.domain.usecases.CanReach
import com.circaso.users.domain.usecases.CheckBlockedEitherWay
import com.circaso.users.domain.usecases.FilterReachable
import com.circaso.users.domain.usecases.GetProfiles
import com.circaso.users.presentation.api.ReachPolicyImpl
import com.circaso.users.presentation.services.DirectoryService
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsersTest {
    private fun <T> Resource<T>.data(): T = (this as Resource.Success).data

    private fun Resource<*>.errorCode(): String = (this as Resource.Error).error.code

    private fun change(
        version: Long,
        name: String? = null,
        about: String? = null,
    ) = ProfileChange(name, about, false, null, null, null, version)

    private fun phone(raw: String) = PhoneNumber.parse(raw)!!

    // ---- profile ----

    @Test
    fun `a profile is created empty on first read`() =
        runTest {
            val h = Harness()
            val id = UserId.new()
            val mine = h.getMyProfile(id).data()
            assertEquals("Circaso user", mine.profile.displayName)
            assertNull(mine.profile.handle)
            assertEquals(AboutVisibility.CONTACTS, mine.profile.privacy.about)
        }

    @Test
    fun `an update bumps the version, tells contacts, and a stale version conflicts`() =
        runTest {
            val h = Harness()
            val amina = h.person("Amina")
            val brian = h.person("Brian")
            h.connect(brian, ConnectVia.QR, h.createInvite(amina).data().token) // brian saves amina

            val first = h.updateProfile(amina, change(version = 0, name = "Amina O.")).data()
            assertEquals("Amina O.", first.profile.displayName)
            assertEquals(1, first.profile.version)
            assertTrue("users.ProfileUpdated" in h.users.eventTypes())
            assertTrue(h.frames.sent.any { it.to == brian && it.type == "profile.updated" })

            assertEquals(
                "users.version_conflict",
                h.updateProfile(amina, change(version = 0, name = "Again")).errorCode(),
            )
            assertEquals(
                "users.profile_invalid",
                h.updateProfile(amina, change(version = 1, name = "x".repeat(41))).errorCode(),
            )
            assertEquals(
                "users.profile_invalid",
                h.updateProfile(amina, change(version = 1, about = "y".repeat(141))).errorCode(),
            )
        }

    @Test
    fun `an avatar must be the caller's own, an allowed type and size`() =
        runTest {
            val h = Harness()
            val id = h.person("Amina")
            assertEquals("users.avatar_invalid", h.prepareAvatar(id, "image/gif", 100).errorCode())
            assertEquals("users.avatar_invalid", h.prepareAvatar(id, "image/png", 6L * 1024 * 1024).errorCode())

            val upload = h.prepareAvatar(id, "image/png", 5000).data()
            assertEquals(
                "users.avatar_invalid",
                h.setAvatar(id, upload.objectKey).errorCode(),
                "nothing was uploaded yet",
            )
            h.avatars.objects[upload.objectKey] = StoredAvatar(5000, "image/png")
            assertEquals(
                "users.avatar_invalid",
                h.setAvatar(id, "avatars/${UserId.new()}/x.png").errorCode(),
                "someone else's key",
            )
            assertEquals("https://cdn.test/${upload.objectKey}", h.setAvatar(id, upload.objectKey).data().avatarUrl)
        }

    // ---- handles ----

    @Test
    fun `handle rules, claims, hold and the 30 day cooldown`() =
        runTest {
            val h = Harness()
            val amina = h.person("Amina")
            val brian = h.person("Brian")

            assertEquals("users.handle_invalid", h.claimHandle(amina, "ab").errorCode())
            assertEquals("users.handle_invalid", h.claimHandle(amina, "Circaso").errorCode())
            assertEquals("users.handle_invalid", h.claimHandle(amina, "1amina").errorCode())

            assertEquals(
                "amina",
                h
                    .claimHandle(amina, "@Amina")
                    .data()
                    .profile.handle,
            )
            assertEquals("users.handle_taken", h.claimHandle(brian, "amina").errorCode())
            val check = h.checkHandle(brian, "amina").data()
            assertEquals(HandleAvailability.TAKEN, check.availability)
            assertNotNull(check.suggestion)
            assertEquals(HandleAvailability.INVALID, h.checkHandle(brian, "!!").data().availability)

            // a second change inside 30 days is refused
            assertEquals("users.handle_cooldown", h.claimHandle(amina, "amina_o").errorCode())
        }

    @Test
    fun `a released handle is held for its old owner`() =
        runTest {
            val h = Harness()
            val amina = h.person("Amina")
            val brian = h.person("Brian")
            h.claimHandle(amina, "amina")
            // pretend the cooldown has passed
            h.users.profiles[amina] =
                h.users.profiles[amina]!!.copy(
                    handleChangedAt =
                        Instant
                            .now()
                            .minusSeconds(31L * 86_400),
                )
            h.claimHandle(amina, "amina_o").data()

            assertNotNull(h.users.handles["amina"]?.releasedAt, "the old handle is on hold")
            assertEquals("users.handle_taken", h.claimHandle(brian, "amina").errorCode())
            assertEquals(
                "users.handle_not_found",
                h.lookupHandle(brian, "amina").errorCode(),
                "a held handle finds no one",
            )
        }

    // ---- contact sync ----

    @Test
    fun `sync makes contacts of matches, remembers the rest as hashes and never re-adds a removed contact`() =
        runTest {
            val h = Harness()
            val me = h.person("Me")
            val amina = h.person("Amina", "+254712345678")
            val sync =
                SyncCommand(me, UUID.randomUUID(), 1, false, setOf(phone("+254712345678"), phone("+254799000111")))

            val matches = h.syncContacts(sync).data()
            assertEquals(listOf(amina), matches.map { it.user.userId })
            assertEquals(ContactStatus.ACTIVE, h.users.contacts[me to amina]?.status)
            assertEquals(
                listOf(h.hmacOf("+254799000111")),
                h.users.waiting.map {
                    it.phoneHmac
                },
                "only a hash of the unknown number is kept",
            )

            h.removeContact(me, amina).data()
            val again = h.syncContacts(sync).data()
            assertTrue(again.isEmpty(), "a removed contact is not shown again")
            assertEquals(ContactStatus.REMOVED, h.users.contacts[me to amina]?.status)
        }

    @Test
    fun `a batch over a thousand numbers is refused`() =
        runTest {
            val h = Harness()
            val me = h.person("Me")
            val many = (1..1001).map { phone("+2547${1000000 + it}") }.toSet()
            assertEquals(
                "users.sync_too_large",
                h.syncContacts(SyncCommand(me, UUID.randomUUID(), 1, true, many)).errorCode(),
            )
        }

    @Test
    fun `when a waited-for person joins, the waiting contact appears and is told`() =
        runTest {
            val h = Harness()
            val me = h.person("Me")
            h.syncContacts(SyncCommand(me, UUID.randomUUID(), 1, true, setOf(phone("+254733000111"))))

            val joiner = UserId.new()
            h.directory.statuses[joiner] = AccountStatus.ACTIVE
            h.onActivated(AccountActivated(joiner, h.hmacOf("+254733000111"), AccountActivated.Hints("Zawadi"))).data()

            assertEquals("Zawadi", h.users.profiles[joiner]?.displayName)
            assertEquals(ContactStatus.ACTIVE, h.users.contacts[me to joiner]?.status)
            assertTrue(h.users.waiting.isEmpty())
            assertTrue(h.frames.sent.any { it.to == me && it.type == "contact.joined" })
            // safe to run twice
            h.onActivated(AccountActivated(joiner, h.hmacOf("+254733000111"), AccountActivated.Hints("Zawadi"))).data()
        }

    // ---- reach ----

    @Test
    fun `the reach rule needs an active account, no block and an active connection`() =
        runTest {
            val h = Harness()
            val me = h.person("Me")
            val amina = h.person("Amina", "+254712345678")
            assertFalse(h.reach.canReach(me, amina), "not connected")

            h.syncContacts(SyncCommand(me, UUID.randomUUID(), 1, true, setOf(phone("+254712345678"))))
            assertTrue(h.reach.canReach(me, amina))
            assertFalse(
                h.reach.canReach(amina, me),
                "it does not need the other side to have you saved... but you need them",
            )

            h.directory.statuses[amina] = AccountStatus.SUSPENDED
            h.reachCache.invalidateTarget(amina)
            assertFalse(h.reach.canReach(me, amina), "a suspended account cannot be reached")
            h.directory.statuses[amina] = AccountStatus.ACTIVE
            h.reachCache.invalidateTarget(amina)

            h.blockUser(amina, me).data()
            assertFalse(h.reach.canReach(me, amina), "a block overrides the connection, whoever made it")
            h.unblockUser(amina, me).data()
            assertTrue(h.reach.canReach(me, amina))

            h.removeContact(me, amina).data()
            assertFalse(h.reach.canReach(me, amina))
        }

    @Test
    fun `a block shows from either side, and lifting it clears it`() =
        runTest {
            val h = Harness()
            val me = h.person("Me")
            val amina = h.person("Amina")
            assertFalse(h.blockedEitherWay(me, amina).data())

            h.blockUser(amina, me).data()
            assertTrue(h.blockedEitherWay(me, amina).data(), "seen from the person blocked")
            assertTrue(h.blockedEitherWay(amina, me).data(), "and from the one who blocked")

            h.unblockUser(amina, me).data()
            assertFalse(h.blockedEitherWay(me, amina).data())
        }

    @Test
    fun `the block check fails closed, so if blocks can't be read, the two count as blocked`() =
        runTest {
            val h = Harness()
            val down =
                object : BlockRepository by h.users {
                    override suspend fun eitherWay(a: UserId, b: UserId): Boolean = error("database down")
                }
            val directory =
                DirectoryService(
                    CanReach(h.reach),
                    FilterReachable(h.reach),
                    GetProfiles(h.users, ProfileViews(FakeAvatars(), h.users)),
                    h.replay,
                    CheckBlockedEitherWay(down),
                )

            assertTrue(ReachPolicyImpl(directory).blockedEitherWay(UserId.new(), UserId.new()))
        }

    @Test
    fun `blocking yourself is refused and blocking twice is harmless`() =
        runTest {
            val h = Harness()
            val me = h.person("Me")
            val other = h.person("Other")
            assertEquals("users.cannot_block_self", h.blockUser(me, me).errorCode())
            h.blockUser(me, other).data()
            h.blockUser(me, other).data()
            assertEquals(1, h.users.eventTypes().count { it == "users.UserBlocked" })
            assertEquals(listOf(other), h.listBlocks(me).data().map { it.userId })
        }

    // ---- connections ----

    @Test
    fun `a QR code or invite connects both ways, a typed handle only one way`() =
        runTest {
            val h = Harness()
            val amina = h.person("Amina")
            val brian = h.person("Brian")
            val carol = h.person("Carol")

            val invite = h.createInvite(amina).data()
            assertTrue(invite.url.endsWith(invite.token))
            val viaQr = h.connect(brian, ConnectVia.QR, invite.qrPayload).data()
            assertTrue(viaQr.created)
            assertEquals(ContactStatus.ACTIVE, h.users.contacts[amina to brian]?.status, "both directions")
            assertTrue(h.reach.canReach(brian, amina) && h.reach.canReach(amina, brian))
            assertTrue(h.frames.sent.count { it.type == "connection.added" } == 2)
            assertFalse(
                h.connect(brian, ConnectVia.INVITE, invite.url).data().created,
                "connecting again changes nothing",
            )

            h.claimHandle(carol, "carol")
            val viaHandle = h.connect(amina, ConnectVia.HANDLE, "@carol").data()
            assertTrue(viaHandle.created)
            assertTrue(h.reach.canReach(amina, carol))
            assertFalse(h.reach.canReach(carol, amina), "carol can reach back only after connecting too")
        }

    @Test
    fun `invites expire, can be revoked by their owner only, and a block hides everything`() =
        runTest {
            val h = Harness()
            val amina = h.person("Amina")
            val brian = h.person("Brian")
            val invite = h.createInvite(amina).data()

            assertEquals("users.invite_invalid", h.connect(brian, ConnectVia.INVITE, "inv_unknown").errorCode())
            assertEquals("users.invite_invalid", h.revokeInvite(brian, invite.token).errorCode())
            h.revokeInvite(amina, invite.token).data()
            assertEquals("users.invite_invalid", h.connect(brian, ConnectVia.INVITE, invite.token).errorCode())

            val second = h.createInvite(amina).data()
            h.blockUser(amina, brian).data()
            assertEquals("users.not_reachable", h.connect(brian, ConnectVia.QR, second.token).errorCode())
        }

    @Test
    fun `a handle lookup finds no one who turned lookup off or blocked the caller, and is limited to 30 an hour`() =
        runTest {
            val h = Harness()
            val amina = h.person("Amina")
            val brian = h.person("Brian")
            h.claimHandle(amina, "amina")

            assertEquals(amina, h.lookupHandle(brian, "amina").data().userId)
            h
                .updateProfile(
                    amina,
                    ProfileChange(null, null, false, null, null, false, version = h.users.profiles[amina]!!.version),
                ).data()
            assertEquals("users.handle_not_found", h.lookupHandle(brian, "amina").errorCode(), "lookup is off")
            assertEquals(
                "users.handle_not_found",
                h.lookupHandle(brian, "nobody").errorCode(),
                "indistinguishable from a handle that doesn't exist",
            )

            val searcher = h.person("Searcher")
            repeat(30) { h.lookupHandle(searcher, "nobody") }
            assertEquals("request.rate_limited", h.lookupHandle(searcher, "nobody").errorCode())
        }

    // ---- what each viewer sees ----

    @Test
    fun `the about line is for contacts and nobody is shown a phone number`() =
        runTest {
            val h = Harness()
            val amina = h.person("Amina")
            val brian = h.person("Brian")
            val stranger = h.person("Stranger")
            h.updateProfile(amina, change(version = 0, about = "Chama treasurer")).data()
            h.connect(brian, ConnectVia.QR, h.createInvite(amina).data().token) // amina now has brian saved

            assertEquals("Chama treasurer", h.getPublicProfile(brian, amina).data().about)
            assertEquals("users.not_reachable", h.getPublicProfile(stranger, amina).errorCode())
            assertEquals("Chama treasurer", h.getPublicProfile(amina, amina).data().about)
            // PublicProfile has no phone field at all: it cannot leak one
            assertTrue(
                PublicProfile::class.java.declaredFields.none {
                    it.name.contains("phone", ignoreCase = true)
                },
            )
        }

    @Test
    fun `the contact list pages and hides removed contacts`() =
        runTest {
            val h = Harness()
            val me = h.person("Me")
            val people = (1..5).map { h.person("Person $it") }
            people.forEach {
                h.users.commit(
                    UsersChanges(
                        contacts =
                            listOf(
                                Contact(
                                    me,
                                    it,
                                    ContactSource.PHONEBOOK,
                                    ContactStatus.ACTIVE,
                                    null,
                                    Instant.now(),
                                ),
                            ),
                    ),
                )
            }
            h.removeContact(me, people[0]).data()

            val first = h.listContacts(me, null, 2).data()
            assertEquals(2, first.entries.size)
            assertNotNull(first.nextCursor)
            val rest = h.listContacts(me, first.nextCursor, 10).data()
            assertEquals(2, rest.entries.size)
            assertNull(rest.nextCursor, "no cursor when there is no next page")
            assertEquals(4, (first.entries + rest.entries).map { it.profile.userId }.toSet().size)
            assertFalse(people[0] in (first.entries + rest.entries).map { it.profile.userId })
        }

    @Test
    fun `the assistant can find a contact by name`() =
        runTest {
            val h = Harness()
            val me = h.person("Me")
            val mum = h.person("Mum Wanjiku")
            h.users.commit(
                UsersChanges(
                    contacts =
                        listOf(
                            Contact(
                                me,
                                mum,
                                ContactSource.PHONEBOOK,
                                ContactStatus.ACTIVE,
                                null,
                                Instant.now(),
                            ),
                        ),
                ),
            )

            assertEquals(listOf(mum), h.searchContacts(me, "mum", 5).data().map { it.userId })
            assertTrue(h.searchContacts(me, "zzz", 5).data().isEmpty())
        }

    // ---- replay ----

    @Test
    fun `replay returns the missed frames, or null when the gap is older than the kept frames`() =
        runTest {
            val h = Harness()
            val me = UserId.new()
            h.frames.logged = listOf(LoggedFrame(5, "{}"), LoggedFrame(6, "{}"))
            h.frames.latest = 6
            assertEquals(listOf(5L, 6L), h.replay(me, 4).data()!!.map { it.seq })
            assertNull(h.replay(me, 1).data(), "frames 2 to 4 are gone, so the phone must reload")
            h.frames.logged = emptyList()
            h.frames.latest = 3
            assertEquals(emptyList(), h.replay(me, 3).data())
            assertNull(h.replay(me, 0).data())
        }

    // ---- account deletion ----

    @Test
    fun `deleting an account removes its profile, contacts both ways and blocks, and holds the handle`() =
        runTest {
            val h = Harness()
            val me = h.person("Me")
            val other = h.person("Other")
            h.claimHandle(me, "me_user")
            h.connect(other, ConnectVia.QR, h.createInvite(me).data().token)
            h.blockUser(me, h.person("Pest")).data()

            h.deleteData(AccountDeleted(me)).data()

            assertNull(h.users.profiles[me])
            assertTrue(
                h.users.contacts.keys
                    .none { it.first == me || it.second == me },
            )
            assertTrue(
                h.users.blocks.keys
                    .none { it.first == me },
            )
            assertNotNull(h.users.handles["me_user"]?.releasedAt)
        }
}
