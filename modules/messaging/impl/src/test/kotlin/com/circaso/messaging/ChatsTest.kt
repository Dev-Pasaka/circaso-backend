package com.circaso.messaging

import com.circaso.messaging.api.ChatCreated
import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.ChatUpdatedFrame
import com.circaso.messaging.api.GroupDeleted
import com.circaso.messaging.api.MemberAdded
import com.circaso.messaging.api.MemberAddedFrame
import com.circaso.messaging.api.MemberRemoved
import com.circaso.messaging.api.MemberRemovedFrame
import com.circaso.messaging.domain.models.KeyBundle
import com.circaso.messaging.domain.models.KyberPreKey
import com.circaso.messaging.domain.models.Role
import com.circaso.messaging.domain.models.SignedPreKey
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.shared.DeviceId
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.getOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M4 to M11, `ChatDirectory`, the shared-group bundle fetch and the replay, against in-memory fakes. */
class ChatsTest {
    private val amina = UserId.new()
    private val brian = UserId.new()
    private val chidi = UserId.new()
    private val dalia = UserId.new()

    // ---- M4 direct chats ----

    @Test
    fun `two people opening their chat at the same moment get the same one, made once`() =
        runTest {
            val h = ChatHarness()
            h.connect(amina, brian)
            h.connect(brian, amina)

            val opened =
                listOf(
                    async { h.openDirectChat(amina, brian).data() },
                    async { h.openDirectChat(brian, amina).data() },
                ).awaitAll()

            assertEquals(1, opened.map { it.chat.chatId }.toSet().size, "one chat for the pair")
            assertEquals(listOf(false, true), opened.map { it.created!! }.sorted())
            assertEquals(1, h.chats.chats.size)
            assertEquals(listOf(ChatCreated.TYPE), h.outbox.types(), "created once")
            assertEquals(
                setOf(amina, brian),
                opened
                    .first()
                    .members
                    .map { it.profile.userId }
                    .toSet(),
            )
            assertTrue(opened.first().members.all { it.role == Role.MEMBER })
            assertNull(opened.first().chat.sealedMeta)
        }

    @Test
    fun `opening again returns the existing chat`() =
        runTest {
            val h = ChatHarness()
            h.connect(amina, brian)

            val first = h.openDirectChat(amina, brian).data()
            val again = h.openDirectChat(amina, brian).data()

            assertEquals(first.chat.chatId, again.chat.chatId)
            assertEquals(true, first.created)
            assertEquals(false, again.created)
        }

    @Test
    fun `a direct chat needs reach, and not with yourself`() =
        runTest {
            val h = ChatHarness()

            assertEquals("users.not_reachable", h.openDirectChat(amina, brian).errorCode())
            assertTrue(h.chats.chats.isEmpty(), "nothing is written for someone you can't reach")
            assertEquals("request.malformed", h.openDirectChat(amina, amina).errorCode())
        }

    // ---- M5 groups ----

    @Test
    fun `a group leaves out whoever the creator can't reach, without a reason`() =
        runTest {
            val h = ChatHarness()
            h.connect(amina, listOf(brian, chidi))

            val group = h.createGroup(h.command(amina, setOf(brian, chidi, dalia, amina))).data()

            assertEquals(listOf(dalia), group.notAdded)
            assertEquals(listOf(brian, chidi).sortedBy { it.toString() }, group.invited)
            assertEquals(1, group.chat.memberCount, "the people asked for are invited, not put in")
            assertEquals(Role.OWNER, h.role(group.chat.chatId, amina))
            assertNull(h.role(group.chat.chatId, brian))
            assertNull(h.role(group.chat.chatId, dalia))
            assertEquals(h.sealed, group.chat.sealedMeta)
            assertEquals(1, group.chat.metaVersion)
            assertFalse(group.chat.settings.membersCanAdd, "admins add members by default")
            assertTrue(h.frames.to(dalia).isEmpty(), "the person left out hears nothing")
        }

    @Test
    fun `one member added frame per join tells every member who joined`() =
        runTest {
            val h = ChatHarness()

            val group = h.group(amina, listOf(brian, chidi))

            val added = h.frames.sent.filter { it.type == "member.added" }
            val chidiJoined = added.filter { it.data<MemberAddedFrame>().userIds == listOf(chidi) }
            assertEquals(setOf(amina, brian, chidi), chidiJoined.map { it.to }.toSet(), "every member, Chidi too")
            val frame = chidiJoined.first().data<MemberAddedFrame>()
            assertEquals(group.chat.chatId, frame.chatId)
            assertEquals(amina, frame.byUserId, "invited by Amina")
            assertTrue(frame.joined)
            assertEquals(2 + 3, added.size, "Brian's join to the two of them, Chidi's to the three")
        }

    @Test
    fun `the chat, its owner and the invites are one transaction, and each join another`() =
        runTest {
            val h = ChatHarness()

            val group = h.group(amina, listOf(brian, chidi))

            assertEquals(listOf("chats.create-group", "invites.accept", "invites.accept"), h.transactor.operations)
            assertEquals(listOf(ChatCreated.TYPE, MemberAdded.TYPE, MemberAdded.TYPE), h.outbox.types())
            val added =
                h.outbox.written
                    .drop(1)
                    .map { it.data.toString() }
            assertTrue(added.any { brian.toString() in it } && added.any { chidi.toString() in it })
            assertTrue(
                added.none { amina.toString() in it.substringBefore("byUserId") },
                "the creator is in ChatCreated",
            )
            assertEquals(h.outbox.written, h.bus.published, "published after each transaction, each once")
            assertEquals(3, h.chats.members(group.chat.chatId).size)
        }

    @Test
    fun `the sealed name must be base64 and at most 4 KB`() =
        runTest {
            val h = ChatHarness()
            val big = Base64.getEncoder().encodeToString(ByteArray(4_097))
            val largest = Base64.getEncoder().encodeToString(ByteArray(4_096))

            assertEquals(
                "messaging.group_invalid",
                h.createGroup(h.command(amina, emptySet(), "")).errorCode(),
            )
            assertEquals(
                "messaging.group_invalid",
                h.createGroup(h.command(amina, emptySet(), "not!")).errorCode(),
            )
            assertEquals(
                "messaging.group_invalid",
                h.createGroup(h.command(amina, emptySet(), big)).errorCode(),
            )
            assertNotNull(h.createGroup(h.command(amina, emptySet(), largest)).data())
        }

    // ---- the cap ----

    @Test
    fun `a group holds at most 1,000 people, its creator included`() =
        runTest {
            val h = ChatHarness()
            h.frames.recording = false
            val people = (1..999).map { UserId.new() }.toSet()
            h.connect(amina, people)

            val created = h.createGroup(h.command(amina, people)).data()
            assertEquals(999, created.invited!!.size)
            assertEquals(1_000L, h.frames.count, "an invite to each person, and invites.changed to Amina")
            h.join(created.chat.chatId, people)
            val full = h.getChat(amina, created.chat.chatId).data()
            assertEquals(1_000, full.chat.memberCount)

            val tooMany = people + UserId.new()
            assertEquals(
                "messaging.group_full",
                h.createGroup(h.command(amina, tooMany)).errorCode(),
            )
            val one = UserId.new()
            h.connect(amina, one)
            assertEquals("messaging.group_full", h.addMembers(amina, full.chat.chatId, setOf(one)).errorCode())
        }

    @Test
    fun `joins racing for the last places can't pass the cap between them`() =
        runTest {
            val h = ChatHarness(MessagingConfig(maxGroupMembers = 4))
            val group = h.group(amina, listOf(brian))
            val chatId = group.chat.chatId
            val newcomers = (1..4).map { UserId.new() }
            h.connect(amina, newcomers)
            h.addMembers(amina, chatId, newcomers.take(2).toSet()).data()
            h.addMembers(amina, chatId, newcomers.drop(2).toSet()).data()

            val results =
                newcomers
                    .map { person -> async { h.acceptInvite(person, h.invites.openId(chatId, person)) } }
                    .awaitAll()

            val chat = h.chats.chats[chatId]!!
            assertTrue(chat.memberCount <= 4, "count ${chat.memberCount}")
            assertEquals(4, h.chats.members(chatId).size)
            assertEquals(2, results.count { it.isError("messaging.group_full") })
            assertEquals(2, h.invites.openIn(chatId, h.clock.instant()).size, "a full group leaves their invites open")
        }

    // ---- admin rules ----

    @Test
    fun `only admins add, change the group, remove and set roles`() =
        runTest {
            val h = ChatHarness()
            val group = h.group(amina, listOf(brian, chidi))
            val chatId = group.chat.chatId
            h.connect(brian, dalia)

            assertEquals("messaging.not_admin", h.addMembers(brian, chatId, setOf(dalia)).errorCode())
            assertEquals("messaging.not_admin", h.update(brian, chatId, group.chat.version, h.sealed).errorCode())
            assertEquals("messaging.not_admin", h.removeMember(brian, chatId, chidi).errorCode())
            assertEquals("messaging.not_admin", h.setRole(brian, chatId, chidi, Role.ADMIN).errorCode())

            h.setRole(amina, chatId, brian, Role.ADMIN).data()
            assertEquals(Role.ADMIN, h.role(chatId, brian))
            assertEquals(listOf(dalia), h.addMembers(brian, chatId, setOf(dalia)).data().invited)
        }

    @Test
    fun `a member may add when the group allows it`() =
        runTest {
            val h = ChatHarness()
            val group = h.group(amina, listOf(brian))
            val chatId = group.chat.chatId
            h.connect(brian, chidi)

            val allowed = h.update(amina, chatId, group.chat.version, membersCanAdd = true).data()
            assertTrue(allowed.chat.settings.membersCanAdd)

            h.addAndJoin(brian, chatId, setOf(chidi))
            assertEquals(Role.MEMBER, h.role(chatId, chidi))
            assertEquals(
                brian,
                h.invites.invites.values
                    .single { it.userId == chidi }
                    .invitedBy,
            )
        }

    @Test
    fun `someone not in the group sees no group at all`() =
        runTest {
            val h = ChatHarness()
            val group = h.group(amina, listOf(brian))
            val chatId = group.chat.chatId

            assertEquals("messaging.chat_not_found", h.getChat(dalia, chatId).errorCode())
            assertEquals("messaging.chat_not_found", h.addMembers(dalia, chatId, setOf(chidi)).errorCode())
            assertEquals("messaging.chat_not_found", h.update(dalia, chatId, 1).errorCode())
            assertEquals("messaging.chat_not_found", h.getChat(amina, ChatId.new()).errorCode())
        }

    @Test
    fun `nobody administers a direct chat`() =
        runTest {
            val h = ChatHarness()
            h.connect(amina, listOf(brian, chidi))
            val chatId =
                h
                    .openDirectChat(amina, brian)
                    .data()
                    .chat.chatId

            assertEquals("messaging.not_admin", h.addMembers(amina, chatId, setOf(chidi)).errorCode())
            assertEquals("messaging.not_admin", h.update(amina, chatId, 1, h.sealed).errorCode())
            assertEquals("messaging.not_a_group", h.removeMember(amina, chatId, brian).errorCode())
            assertEquals("messaging.not_a_group", h.leaveGroup(amina, chatId).errorCode())
            assertEquals("messaging.not_admin", h.setRole(amina, chatId, brian, Role.ADMIN).errorCode())
        }

    // ---- not-reachable adds ----

    @Test
    fun `an add leaves out whoever the adder can't reach, and skips members already in`() =
        runTest {
            val h = ChatHarness()
            val group = h.group(amina, listOf(brian))
            h.connect(amina, chidi)

            val result = h.addMembers(amina, group.chat.chatId, setOf(brian, chidi, dalia)).data()

            assertEquals(listOf(dalia), result.notAdded)
            assertEquals(listOf(chidi), result.invited, "Brian is in already")
            assertNull(h.role(group.chat.chatId, chidi), "invited, not in")
            assertNull(h.role(group.chat.chatId, dalia))
            assertEquals(2, result.chat.memberCount)
            assertTrue(h.frames.to(dalia).isEmpty(), "the person left out hears nothing")
            h.join(group.chat.chatId, listOf(chidi))
            val types = h.outbox.types().filter { it == MemberAdded.TYPE }
            assertEquals(2, types.size, "brian's join and chidi's; dalia never")
        }

    @Test
    fun `an add of nobody reachable writes nothing`() =
        runTest {
            val h = ChatHarness()
            val group = h.group(amina, listOf(brian))
            val transactions = h.transactor.operations.size

            val result = h.addMembers(amina, group.chat.chatId, setOf(dalia)).data()

            assertEquals(listOf(dalia), result.notAdded)
            assertEquals(transactions, h.transactor.operations.size)
            assertEquals(group.chat.version, h.chats.chats[group.chat.chatId]!!.version)
        }

    // ---- leave and remove ----

    @Test
    fun `an admin removes a member, who is told along with everyone else`() =
        runTest {
            val h = ChatHarness()
            val group = h.group(amina, listOf(brian, chidi))
            val chatId = group.chat.chatId
            h.frames.sent.clear()

            h.removeMember(amina, chatId, brian).data()

            assertNull(h.role(chatId, brian))
            assertEquals(2, h.chats.chats[chatId]!!.memberCount)
            val removed = h.frames.sent.filter { it.type == "member.removed" }
            assertEquals(setOf(amina, brian, chidi), removed.map { it.to }.toSet())
            assertEquals(brian, removed.first().data<MemberRemovedFrame>().userId)
            assertEquals(amina, removed.first().data<MemberRemovedFrame>().byUserId)
            assertEquals(
                "chats.remove-member",
                h.transactor.operations.last(),
                "rows, count and event: one transaction",
            )
            assertEquals(
                MemberRemoved.TYPE,
                h.outbox.written
                    .last()
                    .type,
            )
            assertEquals("messaging.chat_not_found", h.getChat(brian, chatId).errorCode())
            assertTrue(h.listChats(brian).data().isEmpty())
        }

    @Test
    fun `a member leaves on their own`() =
        runTest {
            val h = ChatHarness()
            val group = h.group(amina, listOf(brian, chidi))
            val chatId = group.chat.chatId

            h.leaveGroup(chidi, chatId).data()

            assertNull(h.role(chatId, chidi))
            val event = h.outbox.written.last()
            assertTrue(chidi.toString() in event.data.toString())
            assertEquals(
                listOf(amina, brian)
                    .map {
                        it.toString()
                    }.toSet(),
                h.chats
                    .members(chatId)
                    .map { it.userId.toString() }
                    .toSet(),
            )
        }

    @Test
    fun `nobody removes the owner`() =
        runTest {
            val h = ChatHarness()
            val group = h.group(amina, listOf(brian))
            val chatId = group.chat.chatId
            h.setRole(amina, chatId, brian, Role.ADMIN).data()

            assertEquals("messaging.not_admin", h.removeMember(brian, chatId, amina).errorCode())
            assertEquals(Role.OWNER, h.role(chatId, amina))
        }

    @Test
    fun `an owner who leaves hands the group to the longest-serving admin, through the version check`() =
        runTest {
            val h = ChatHarness()
            val chatId = h.group(amina, listOf(brian)).chat.chatId
            h.clock.advance()
            h.connect(amina, listOf(chidi, dalia))
            h.addAndJoin(amina, chatId, setOf(chidi))
            h.clock.advance()
            h.addAndJoin(amina, chatId, setOf(dalia))
            h.setRole(amina, chatId, dalia, Role.ADMIN).data()
            h.setRole(amina, chatId, chidi, Role.ADMIN).data()
            h.frames.sent.clear()

            h.leaveGroup(amina, chatId).data()

            assertEquals(Role.OWNER, h.role(chatId, chidi), "chidi joined before dalia; brian is no admin")
            assertEquals(Role.ADMIN, h.role(chatId, dalia))
            assertNull(h.role(chatId, amina))
            assertEquals(chidi, h.chats.chats[chatId]!!.ownerId, "the chat names the new owner")
            assertEquals(Role.OWNER, h.chats.members[chatId]!![chidi]!!.role, "and the row mirrors it")
            val updated = h.frames.sent.filter { it.type == "chat.updated" }
            assertEquals(setOf(brian, chidi, dalia), updated.map { it.to }.toSet())
            assertEquals(h.chats.chats[chatId]!!.version, updated.first().data<ChatUpdatedFrame>().version)
            assertEquals(1, h.chats.members(chatId).count { it.role == Role.OWNER })
        }

    @Test
    fun `with no admin, the longest-serving member becomes owner`() =
        runTest {
            val h = ChatHarness()
            val chatId = h.group(amina, listOf(brian)).chat.chatId
            h.clock.advance()
            h.connect(amina, chidi)
            h.addAndJoin(amina, chatId, setOf(chidi))

            h.leaveGroup(amina, chatId).data()

            assertEquals(Role.OWNER, h.role(chatId, brian))
            assertEquals(Role.MEMBER, h.role(chatId, chidi))
        }

    @Test
    fun `the last member leaving deletes the group`() =
        runTest {
            val h = ChatHarness()
            val group = h.group(amina)

            h.leaveGroup(amina, group.chat.chatId).data()

            assertNull(h.chats.chats[group.chat.chatId])
            assertEquals(
                listOf(GroupDeleted.TYPE, MemberRemoved.TYPE),
                h.outbox
                    .types()
                    .takeLast(2)
                    .sorted(),
            )
        }

    // ---- roles and ownership ----

    @Test
    fun `the owner hands over ownership and becomes an admin, then may leave`() =
        runTest {
            val h = ChatHarness()
            val group = h.group(amina, listOf(brian, chidi))
            val chatId = group.chat.chatId

            val handed = h.setRole(amina, chatId, brian, Role.OWNER).data()

            assertEquals(Role.OWNER, h.role(chatId, brian))
            assertEquals(Role.ADMIN, h.role(chatId, amina))
            assertEquals(Role.OWNER, h.chats.members[chatId]!![brian]!!.role, "the rows follow the chat")
            assertEquals(Role.ADMIN, h.chats.members[chatId]!![amina]!!.role)
            assertEquals(1, handed.members.count { it.role == Role.OWNER })
            assertEquals(group.chat.version + 1, handed.chat.version, "a role change moves the version on")
            val updated = h.frames.sent.filter { it.type == "chat.updated" }
            assertEquals(setOf(amina, brian, chidi), updated.map { it.to }.toSet())
            h.leaveGroup(amina, chatId).data()
            assertNull(h.role(chatId, amina))
        }

    @Test
    fun `only the owner hands over, and the owner's role changes only that way`() =
        runTest {
            val h = ChatHarness()
            val group = h.group(amina, listOf(brian, chidi))
            val chatId = group.chat.chatId
            h.setRole(amina, chatId, brian, Role.ADMIN).data()

            assertEquals("messaging.not_admin", h.setRole(brian, chatId, chidi, Role.OWNER).errorCode())
            assertEquals("messaging.not_admin", h.setRole(brian, chatId, amina, Role.MEMBER).errorCode())
            assertEquals("messaging.not_admin", h.setRole(amina, chatId, amina, Role.MEMBER).errorCode())
            h.setRole(brian, chatId, brian, Role.MEMBER).data()
            assertEquals(Role.MEMBER, h.role(chatId, brian), "an admin may step down")
            assertEquals("messaging.chat_not_found", h.setRole(amina, chatId, dalia, Role.ADMIN).errorCode())
        }

    // ---- M8 and the version ----

    @Test
    fun `a new seal raises metaVersion and tells every member`() =
        runTest {
            val h = ChatHarness()
            val group = h.group(amina, listOf(brian))
            val resealed = Base64.getEncoder().encodeToString(ByteArray(32) { 9 })
            h.frames.sent.clear()

            val updated = h.update(amina, group.chat.chatId, group.chat.version, resealed).data()

            assertEquals(resealed, updated.chat.sealedMeta)
            assertEquals(2, updated.chat.metaVersion)
            assertEquals(group.chat.version + 1, updated.chat.version)
            val frames = h.frames.sent.filter { it.type == "chat.updated" }
            assertEquals(setOf(amina, brian), frames.map { it.to }.toSet())
            assertEquals(
                ChatUpdatedFrame(group.chat.chatId, metaVersion = 2, version = group.chat.version + 1),
                frames.first().data<ChatUpdatedFrame>(),
            )
        }

    @Test
    fun `a change made from an old version is refused`() =
        runTest {
            val h = ChatHarness()
            val group = h.group(amina, listOf(brian))
            val chatId = group.chat.chatId
            h.update(amina, chatId, group.chat.version, membersCanAdd = true).data()

            assertEquals(
                "messaging.version_conflict",
                h.update(amina, chatId, group.chat.version, h.sealed).errorCode(),
            )
        }

    @Test
    fun `membership changes move the version on`() =
        runTest {
            val h = ChatHarness()
            val group = h.group(amina, listOf(brian))
            h.connect(amina, chidi)

            val invited = h.addMembers(amina, group.chat.chatId, setOf(chidi)).data()
            assertEquals(group.chat.version, invited.chat.version, "an invite isn't a membership change")
            val added = h.acceptInvite(chidi, h.invites.openId(group.chat.chatId, chidi)).data()

            assertEquals(group.chat.version + 1, added.chat.version)
            assertEquals(
                "messaging.version_conflict",
                h.update(amina, group.chat.chatId, group.chat.version).errorCode(),
            )
        }

    // ---- M6, ChatDirectory and the members cache ----

    @Test
    fun `the chat list has every chat with members and roles`() =
        runTest {
            val h = ChatHarness()
            h.connect(amina, chidi)
            val group = h.group(amina, listOf(brian))
            val direct = h.openDirectChat(amina, chidi).data()

            val list = h.listChats(amina).data()

            assertEquals(setOf(group.chat.chatId, direct.chat.chatId), list.map { it.chat.chatId }.toSet())
            assertTrue(list.all { view -> view.members.any { it.profile.userId == amina } })
            assertEquals(1, h.listChats(brian).data().size)
        }

    @Test
    fun `ChatDirectory answers who is in a chat, through the cache, cleared on change`() =
        runTest {
            val h = ChatHarness()
            val group = h.group(amina, listOf(brian))
            val chatId = group.chat.chatId
            h.cache.expireMarkers()

            assertEquals(setOf(amina, brian), h.directory.members(chatId))
            assertEquals(setOf(amina, brian), h.cache.entries[chatId], "cached after the first read")
            assertTrue(h.directory.isMember(chatId, brian))
            assertFalse(h.directory.isMember(chatId, chidi))

            h.connect(amina, chidi)
            h.addMembers(amina, chatId, setOf(chidi)).data()
            assertFalse(h.directory.isMember(chatId, chidi), "an invite isn't a membership")
            h.join(chatId, listOf(chidi))
            assertNull(h.cache.entries[chatId], "a join clears it")
            assertTrue(h.directory.isMember(chatId, chidi))

            h.removeMember(amina, chatId, brian).data()
            assertNull(h.cache.entries[chatId], "a removal clears it")
            assertFalse(h.directory.isMember(chatId, brian))

            assertEquals(emptySet(), h.directory.members(ChatId.new()), "no chat, no members")
        }

    // ---- M3 shared group ----

    @Test
    fun `someone who shares a group may fetch a bundle without a connection`() =
        runTest {
            val h = ChatHarness()
            h.keys.bundles[chidi] =
                KeyBundle(
                    chidi,
                    DeviceId.new(),
                    "BcF2",
                    SignedPreKey(7, "BXa", "kq3"),
                    KyberPreKey(3, "CAE", "9fQ"),
                    Instant.now(),
                )
            h.keys.bundles[dalia] =
                KeyBundle(
                    dalia,
                    DeviceId.new(),
                    "BcF2",
                    SignedPreKey(7, "BXa", "kq3"),
                    KyberPreKey(3, "CAE", "9fQ"),
                    Instant.now(),
                )

            assertEquals("users.not_reachable", h.fetchKeyBundle(brian, chidi).errorCode())
            h.group(amina, listOf(brian, chidi))

            assertEquals(chidi, h.fetchKeyBundle(brian, chidi).data().userId, "brian and chidi share amina's group")
            assertEquals("users.not_reachable", h.fetchKeyBundle(brian, dalia).errorCode(), "dalia shares nothing")
            h.openDirectChat(amina, brian).data()
            assertEquals("users.not_reachable", h.fetchKeyBundle(brian, dalia).errorCode())
        }

    @Test
    fun `a shared group never overrides a block`() =
        runTest {
            val h = ChatHarness()
            h.keys.bundles[chidi] =
                KeyBundle(
                    chidi,
                    DeviceId.new(),
                    "BcF2",
                    SignedPreKey(7, "BXa", "kq3"),
                    KyberPreKey(3, "CAE", "9fQ"),
                    Instant.now(),
                )
            h.group(amina, listOf(brian, chidi))
            h.reach.blocks += chidi to brian

            assertEquals("users.not_reachable", h.fetchKeyBundle(brian, chidi).errorCode(), "chidi blocked brian")
            h.reach.blocks.clear()
            h.reach.blocks += brian to chidi
            assertEquals("users.not_reachable", h.fetchKeyBundle(brian, chidi).errorCode(), "and the other way")
            h.reach.blocks.clear()
            assertEquals(chidi, h.fetchKeyBundle(brian, chidi).data().userId)
        }

    @Test
    fun `a direct chat is not a shared group`() =
        runTest {
            val h = ChatHarness()
            h.keys.bundles[brian] =
                KeyBundle(
                    brian,
                    DeviceId.new(),
                    "BcF2",
                    SignedPreKey(7, "BXa", "kq3"),
                    KyberPreKey(3, "CAE", "9fQ"),
                    Instant.now(),
                )
            h.connect(amina, brian)
            h.openDirectChat(amina, brian).data()
            h.reach.pairs.clear()

            assertEquals("users.not_reachable", h.fetchKeyBundle(amina, brian).errorCode())
        }

    // ---- replay ----

    @Test
    fun `missed membership frames are replayed in order, and an expired gap asks for a resync`() =
        runTest {
            val h = ChatHarness()
            val group = h.group(amina, listOf(brian))
            h.connect(amina, chidi)
            h.addAndJoin(amina, group.chat.chatId, setOf(chidi))
            h.update(amina, group.chat.chatId, h.chats.chats[group.chat.chatId]!!.version, membersCanAdd = true).data()

            val all = h.replayFrames(brian, 0).data()!!
            assertEquals(
                listOf("invite.received", "member.added", "member.added", "chat.updated"),
                all.map { it.json },
                "Brian's invite and join, Chidi's join (not his invite) and the change",
            )
            assertEquals(listOf(1L, 2L, 3L, 4L), all.map { it.seq })
            assertEquals(listOf(4L), h.replayFrames(brian, 3).data()!!.map { it.seq })
            assertEquals(emptyList(), h.replayFrames(brian, 4).data())

            h.frames.expire(brian, 1)
            assertNull(h.replayFrames(brian, 0).getOrNull(), "frame 1 is gone: the phone must resync")
        }

    private fun Resource<*>.isError(code: String) = this is Resource.Error && error.code == code
}
