package com.circaso.messaging

import com.circaso.messaging.api.ChatCreated
import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.GroupDeleted
import com.circaso.messaging.api.MemberAdded
import com.circaso.messaging.api.MemberRemoved
import com.circaso.messaging.domain.models.CreateGroupCommand
import com.circaso.messaging.domain.models.KeyBundle
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
            assertEquals(listOf(ChatCreated.TYPE), h.chats.eventTypes(), "created once")
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
    fun `a claimed pair whose chat was never written is written by the next opener`() =
        runTest {
            val h = ChatHarness()
            h.connect(amina, brian)
            val claimed = ChatId.new()
            h.chats.claims[h.pair(amina, brian)] = claimed

            val opened = h.openDirectChat(amina, brian).data()

            assertEquals(claimed, opened.chat.chatId)
            assertEquals(2, h.chats.members(claimed).size)
        }

    @Test
    fun `a direct chat needs reach, and not with yourself`() =
        runTest {
            val h = ChatHarness()

            assertEquals("users.not_reachable", h.openDirectChat(amina, brian).errorCode())
            assertTrue(h.chats.claims.isEmpty(), "nothing is claimed for someone you can't reach")
            assertEquals("request.malformed", h.openDirectChat(amina, amina).errorCode())
        }

    // ---- M5 groups ----

    @Test
    fun `a group leaves out whoever the creator can't reach, without a reason`() =
        runTest {
            val h = ChatHarness()
            h.connect(amina, listOf(brian, chidi))

            val group = h.createGroup(CreateGroupCommand(amina, h.sealed, setOf(brian, chidi, dalia, amina))).data()

            assertEquals(listOf(dalia), group.notAdded)
            assertEquals(3, group.chat.memberCount)
            assertEquals(Role.OWNER, h.role(group.chat.chatId, amina))
            assertEquals(Role.MEMBER, h.role(group.chat.chatId, brian))
            assertNull(h.role(group.chat.chatId, dalia))
            assertEquals(h.sealed, group.chat.sealedMeta)
            assertEquals(1, group.chat.metaVersion)
            assertFalse(group.chat.settings.membersCanAdd, "admins add members by default")
            assertTrue(h.frames.to(dalia).isEmpty(), "the person left out hears nothing")
        }

    @Test
    fun `each person added gets one member added frame, sent to every member`() =
        runTest {
            val h = ChatHarness()

            val group = h.group(amina, listOf(brian, chidi))

            val added = h.frames.sent.filter { it.type == "member.added" }
            assertEquals(6, added.size, "two frames, one per person, to each of the three members")
            for (person in listOf(amina, brian, chidi)) {
                assertEquals(
                    setOf(brian.toString(), chidi.toString()),
                    h.frames
                        .to(person)
                        .map { it.data["userId"] }
                        .toSet(),
                )
            }
            val frame = added.first().data
            assertEquals(group.chat.chatId.toString(), frame["chatId"])
            assertEquals(amina.toString(), frame["byUserId"])
        }

    @Test
    fun `each member's rows and their event are committed together`() =
        runTest {
            val h = ChatHarness()

            val group = h.group(amina, listOf(brian, chidi))

            val commit = h.chats.commits.single()
            assertEquals(listOf(ChatCreated.TYPE), commit.events.map { it.type })
            val owner = commit.joins.first()
            assertEquals(amina, owner.member.userId)
            assertNull(owner.event, "the creator is in ChatCreated")
            for (join in commit.joins.drop(1)) {
                assertEquals(MemberAdded.TYPE, join.event!!.type)
                assertTrue(join.member.userId.toString() in join.event.data.toString())
            }
            assertEquals(group.chat.chatId, commit.chats.single().chatId)
        }

    @Test
    fun `the sealed name must be base64 and at most 4 KB`() =
        runTest {
            val h = ChatHarness()
            val big = Base64.getEncoder().encodeToString(ByteArray(4_097))
            val largest = Base64.getEncoder().encodeToString(ByteArray(4_096))

            assertEquals(
                "messaging.group_invalid",
                h.createGroup(CreateGroupCommand(amina, "", emptySet())).errorCode(),
            )
            assertEquals(
                "messaging.group_invalid",
                h.createGroup(CreateGroupCommand(amina, "not!", emptySet())).errorCode(),
            )
            assertEquals(
                "messaging.group_invalid",
                h.createGroup(CreateGroupCommand(amina, big, emptySet())).errorCode(),
            )
            assertNotNull(h.createGroup(CreateGroupCommand(amina, largest, emptySet())).data())
        }

    // ---- the cap ----

    @Test
    fun `a group holds at most 1,000 people, its creator included`() =
        runTest {
            val h = ChatHarness()
            h.frames.recording = false
            val people = (1..999).map { UserId.new() }.toSet()
            h.connect(amina, people)

            val full = h.createGroup(CreateGroupCommand(amina, h.sealed, people)).data()
            assertEquals(1_000, full.chat.memberCount)
            assertEquals(999L * 1_000, h.frames.count, "one frame per person added, to every member (spec gap)")

            val tooMany = people + UserId.new()
            assertEquals(
                "messaging.group_full",
                h.createGroup(CreateGroupCommand(amina, h.sealed, tooMany)).errorCode(),
            )
            val one = UserId.new()
            h.connect(amina, one)
            assertEquals("messaging.group_full", h.addMembers(amina, full.chat.chatId, setOf(one)).errorCode())
        }

    @Test
    fun `adds racing for the last places can't pass the cap between them`() =
        runTest {
            val h = ChatHarness(MessagingConfig(maxGroupMembers = 4))
            val group = h.group(amina, listOf(brian))
            val newcomers = (1..4).map { UserId.new() }
            h.connect(amina, newcomers)

            val results =
                newcomers
                    .map { person -> async { h.addMembers(amina, group.chat.chatId, setOf(person)) } }
                    .awaitAll()

            val chat = h.chats.chats[group.chat.chatId]!!
            assertTrue(chat.memberCount <= 4, "count ${chat.memberCount}")
            assertEquals(4, h.chats.members(group.chat.chatId).size)
            assertEquals(2, results.count { it.isError("messaging.group_full") })
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
            assertEquals(
                listOf(dalia),
                h.addMembers(brian, chatId, setOf(dalia)).data().members.map { it.profile.userId }.filter {
                    it ==
                        dalia
                },
            )
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

            h.addMembers(brian, chatId, setOf(chidi)).data()
            assertEquals(Role.MEMBER, h.role(chatId, chidi))
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
            assertEquals("messaging.not_admin", h.removeMember(amina, chatId, brian).errorCode())
            assertEquals("messaging.not_admin", h.leaveGroup(amina, chatId).errorCode())
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
            assertEquals(Role.MEMBER, h.role(group.chat.chatId, chidi))
            assertNull(h.role(group.chat.chatId, dalia))
            assertEquals(3, result.chat.memberCount)
            val types = h.chats.eventTypes().filter { it == MemberAdded.TYPE }
            assertEquals(2, types.size, "brian at creation and chidi now; dalia never")
        }

    @Test
    fun `an add of nobody reachable writes nothing`() =
        runTest {
            val h = ChatHarness()
            val group = h.group(amina, listOf(brian))
            val commits = h.chats.commits.size

            val result = h.addMembers(amina, group.chat.chatId, setOf(dalia)).data()

            assertEquals(listOf(dalia), result.notAdded)
            assertEquals(commits, h.chats.commits.size)
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
            assertEquals(brian.toString(), removed.first().data["userId"])
            assertEquals(amina.toString(), removed.first().data["byUserId"])
            val leave =
                h.chats.commits
                    .last()
                    .leaves
                    .single()
            assertEquals(MemberRemoved.TYPE, leave.event!!.type, "the event rides with the rows it describes")
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
            val event =
                h.chats.commits
                    .last()
                    .leaves
                    .single()
                    .event!!
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
    fun `nobody removes the owner, and the owner can't leave while others remain`() =
        runTest {
            val h = ChatHarness()
            val group = h.group(amina, listOf(brian))
            val chatId = group.chat.chatId
            h.setRole(amina, chatId, brian, Role.ADMIN).data()

            assertEquals("messaging.not_admin", h.removeMember(brian, chatId, amina).errorCode())
            assertEquals("messaging.owner_must_hand_over", h.leaveGroup(amina, chatId).errorCode())
            assertEquals(Role.OWNER, h.role(chatId, amina))
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
                h.chats
                    .eventTypes()
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
            assertEquals(
                2,
                h.chats.commits
                    .last()
                    .roles.size,
                "both rows in one commit",
            )
            assertEquals(1, handed.members.count { it.role == Role.OWNER })
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
                mapOf("chatId" to group.chat.chatId.toString(), "metaVersion" to 2, "version" to 2L),
                frames.first().data,
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
    fun `a change that loses the race for the version is refused`() =
        runTest {
            val h = ChatHarness()
            val group = h.group(amina, listOf(brian, chidi))
            val chatId = group.chat.chatId
            h.chats.beforeUpdate = {
                h.chats.beforeUpdate = {}
                h.leaveGroup(chidi, chatId)
            }

            assertEquals(
                "messaging.version_conflict",
                h.update(amina, chatId, group.chat.version, h.sealed).errorCode(),
            )
            assertEquals(1, h.chats.chats[chatId]!!.metaVersion, "nothing was written")
        }

    @Test
    fun `membership changes move the version on`() =
        runTest {
            val h = ChatHarness()
            val group = h.group(amina, listOf(brian))
            h.connect(amina, chidi)

            val added = h.addMembers(amina, group.chat.chatId, setOf(chidi)).data()

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

            assertEquals(setOf(amina, brian), h.directory.members(chatId))
            assertEquals(setOf(amina, brian), h.cache.entries[chatId], "cached after the first read")
            assertTrue(h.directory.isMember(chatId, brian))
            assertFalse(h.directory.isMember(chatId, chidi))

            h.connect(amina, chidi)
            h.addMembers(amina, chatId, setOf(chidi)).data()
            assertNull(h.cache.entries[chatId], "an add clears it")
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
                KeyBundle(chidi, DeviceId.new(), "BcF2", SignedPreKey(7, "BXa", "kq3"), Instant.now())
            h.keys.bundles[dalia] =
                KeyBundle(dalia, DeviceId.new(), "BcF2", SignedPreKey(7, "BXa", "kq3"), Instant.now())

            assertEquals("users.not_reachable", h.fetchKeyBundle(brian, chidi).errorCode())
            h.group(amina, listOf(brian, chidi))

            assertEquals(chidi, h.fetchKeyBundle(brian, chidi).data().userId, "brian and chidi share amina's group")
            assertEquals("users.not_reachable", h.fetchKeyBundle(brian, dalia).errorCode(), "dalia shares nothing")
            h.openDirectChat(amina, brian).data()
            assertEquals("users.not_reachable", h.fetchKeyBundle(brian, dalia).errorCode())
        }

    @Test
    fun `a direct chat is not a shared group`() =
        runTest {
            val h = ChatHarness()
            h.keys.bundles[brian] =
                KeyBundle(brian, DeviceId.new(), "BcF2", SignedPreKey(7, "BXa", "kq3"), Instant.now())
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
            h.addMembers(amina, group.chat.chatId, setOf(chidi)).data()
            h.update(amina, group.chat.chatId, h.chats.chats[group.chat.chatId]!!.version, membersCanAdd = true).data()

            val all = h.replayFrames(brian, 0).data()!!
            assertEquals(listOf("member.added", "member.added", "chat.updated"), all.map { it.json })
            assertEquals(listOf(1L, 2L, 3L), all.map { it.seq })
            assertEquals(listOf(3L), h.replayFrames(brian, 2).data()!!.map { it.seq })
            assertEquals(emptyList(), h.replayFrames(brian, 3).data())

            h.frames.expire(brian, 1)
            assertNull(h.replayFrames(brian, 0).getOrNull(), "frame 1 is gone: the phone must resync")
        }

    private fun Resource<*>.isError(code: String) = this is Resource.Error && error.code == code
}
