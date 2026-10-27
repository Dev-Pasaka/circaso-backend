package com.circaso.messaging

import com.circaso.messaging.api.ChatCreated
import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.GroupDeleted
import com.circaso.messaging.api.MemberAddedFrame
import com.circaso.messaging.api.MemberRemoved
import com.circaso.messaging.domain.models.Role
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Membership changes racing each other, resumed creates, failed writes and the members cache (decision
 * 2026-10-26). The in-memory chats yield at every call, so requests started together interleave step by step.
 */
class ChatRacesTest {
    private val amina = UserId.new()
    private val brian = UserId.new()
    private val chidi = UserId.new()
    private val dalia = UserId.new()

    private fun ChatHarness.owners(chatId: ChatId) =
        chats.members[chatId].orEmpty().keys.count {
            role(chatId, it) ==
                Role.OWNER
        }

    private fun ChatHarness.removedEvents(userId: UserId) =
        chats.commits
            .flatMap { commit -> commit.leaves.mapNotNull { it.event } }
            .filter { it.type == MemberRemoved.TYPE && userId.toString() in it.data.toString() }
            .map { it.eventId }
            .toSet()

    // ---- ownership ----

    @Test
    fun `of two handovers at once, exactly one wins and the group has one owner`() =
        runTest {
            val h = ChatHarness()
            val chatId = h.group(amina, listOf(brian, chidi)).chat.chatId

            val results =
                listOf(
                    async { h.setRole(amina, chatId, brian, Role.OWNER) },
                    async { h.setRole(amina, chatId, chidi, Role.OWNER) },
                ).awaitAll()

            assertEquals(1, results.count { it is Resource.Success })
            assertEquals("messaging.not_admin", results.single { it is Resource.Error }.errorCode())
            assertEquals(1, h.owners(chatId))
            assertEquals(Role.ADMIN, h.role(chatId, amina))
        }

    @Test
    fun `the owner and the next in line leaving at once leave the group one owner`() =
        runTest {
            val h = ChatHarness()
            val chatId = h.group(amina, listOf(brian)).chat.chatId
            h.setRole(amina, chatId, brian, Role.ADMIN).data()
            h.clock.advance()
            h.connect(amina, chidi)
            h.addMembers(amina, chatId, setOf(chidi)).data()

            listOf(
                async { h.leaveGroup(amina, chatId).data() },
                async { h.leaveGroup(brian, chatId).data() },
            ).awaitAll()

            assertEquals(chidi, h.chats.chats[chatId]!!.ownerId)
            assertEquals(Role.OWNER, h.role(chatId, chidi))
            assertEquals(1, h.chats.chats[chatId]!!.memberCount)
        }

    @Test
    fun `a seniority tie between admins goes to the smaller user id`() =
        runTest {
            val h = ChatHarness()
            val chatId = h.group(amina, listOf(brian, chidi)).chat.chatId
            h.setRole(amina, chatId, brian, Role.ADMIN).data()
            h.setRole(amina, chatId, chidi, Role.ADMIN).data()

            h.leaveGroup(amina, chatId).data()

            val first = listOf(brian, chidi).minBy { it.toString() }
            assertEquals(first, h.chats.chats[chatId]!!.ownerId, "both joined when the group was made")
        }

    // ---- leaving and removing ----

    @Test
    fun `an admin removes another admin`() =
        runTest {
            val h = ChatHarness()
            val chatId = h.group(amina, listOf(brian, chidi)).chat.chatId
            h.setRole(amina, chatId, brian, Role.ADMIN).data()
            h.setRole(amina, chatId, chidi, Role.ADMIN).data()

            h.removeMember(brian, chatId, chidi).data()

            assertNull(h.role(chatId, chidi))
        }

    @Test
    fun `the last two leaving at once delete the group, once`() =
        runTest {
            val h = ChatHarness()
            val chatId = h.group(amina, listOf(brian)).chat.chatId

            listOf(
                async { h.leaveGroup(amina, chatId).data() },
                async { h.leaveGroup(brian, chatId).data() },
            ).awaitAll()

            assertNull(h.chats.chats[chatId])
            val types = h.chats.eventTypes()
            assertEquals(1, types.count { it == GroupDeleted.TYPE })
            assertEquals(2, types.count { it == MemberRemoved.TYPE })
        }

    @Test
    fun `a remove and a leave of the same person at once remove them once`() =
        runTest {
            val h = ChatHarness()
            val chatId = h.group(amina, listOf(brian, chidi)).chat.chatId

            val results =
                listOf(
                    async { h.removeMember(amina, chatId, chidi) },
                    async { h.leaveGroup(chidi, chatId) },
                ).awaitAll()

            assertEquals(1, results.count { it is Resource.Success })
            assertEquals("messaging.chat_not_found", results.single { it is Resource.Error }.errorCode())
            assertEquals(1, h.removedEvents(chidi).size, "one MemberRemoved id, however many batches carried it")
            assertEquals(2, h.chats.chats[chatId]!!.memberCount, "one decrement")
        }

    @Test
    fun `a removal whose batch failed is finished by a retry, with one event`() =
        runTest {
            val h = ChatHarness()
            val chatId = h.group(amina, listOf(brian, chidi)).chat.chatId
            h.chats.failCommits = 1

            assertFailsWith<IllegalStateException> { h.leaveGroup(chidi, chatId) }
            assertEquals(Role.MEMBER, h.role(chatId, chidi), "still a member, so the retry can run")
            h.removeMember(amina, chatId, chidi).data()

            assertNull(h.role(chatId, chidi))
            assertNull(h.chats.userChat(chidi, chatId))
            assertEquals(1, h.removedEvents(chidi).size)
            assertEquals(2, h.chats.chats[chatId]!!.memberCount)
        }

    @Test
    fun `a role written as the person leaves doesn't put them back`() =
        runTest {
            val h = ChatHarness()
            val chatId = h.group(amina, listOf(brian, chidi)).chat.chatId
            h.chats.beforeRoleWrite = {
                h.chats.beforeRoleWrite = {}
                h.leaveGroup(brian, chatId).data()
            }

            h.setRole(amina, chatId, brian, Role.ADMIN).data()

            assertNull(h.chats.members[chatId]!![brian])
            assertNull(h.role(chatId, brian))
        }

    @Test
    fun `a successor who leaves just after being chosen isn't put back, and the next in line takes over`() =
        runTest {
            val h = ChatHarness()
            val chatId = h.group(amina, listOf(brian)).chat.chatId
            h.setRole(amina, chatId, brian, Role.ADMIN).data()
            h.clock.advance()
            h.connect(amina, chidi)
            h.addMembers(amina, chatId, setOf(chidi)).data()
            h.chats.beforeRoleWrite = {
                h.chats.beforeRoleWrite = {}
                h.leaveGroup(brian, chatId).data()
            }

            h.leaveGroup(amina, chatId).data()

            assertNull(h.chats.members[chatId]!![brian], "brian, chosen then gone, is not written back")
            assertEquals(chidi, h.chats.chats[chatId]!!.ownerId)
            assertEquals(Role.OWNER, h.role(chatId, chidi))
            assertEquals(1, h.chats.chats[chatId]!!.memberCount)
        }

    // ---- creates and failed writes ----

    @Test
    fun `a create retried with the same key carries on with the same group`() =
        runTest {
            val h = ChatHarness()
            h.connect(amina, listOf(brian, chidi))
            val key = UUID.randomUUID().toString()

            val first = h.createGroup(h.command(amina, setOf(brian), key = key)).data()
            h.frames.sent.clear()
            val again = h.createGroup(h.command(amina, setOf(brian, chidi), key = key)).data()

            assertEquals(first.chat.chatId, again.chat.chatId)
            assertEquals(3, h.chats.members(first.chat.chatId).size)
            assertEquals(3, h.chats.chats[first.chat.chatId]!!.memberCount)
            assertEquals(1, h.chats.eventTypes().count { it == ChatCreated.TYPE })
            val added = h.frames.sent.filter { it.type == "member.added" }
            assertEquals(listOf(chidi), added.first().data<MemberAddedFrame>().userIds, "only the one written now")
        }

    @Test
    fun `a create that failed part-way is finished by its retry`() =
        runTest {
            val h = ChatHarness()
            h.connect(amina, listOf(brian, chidi))
            val key = UUID.randomUUID().toString()
            h.chats.partialJoins = 2

            assertFailsWith<IllegalStateException> { h.createGroup(h.command(amina, setOf(brian, chidi), key = key)) }
            val chatId =
                h.chats.chats.keys
                    .single()
            assertEquals(3, h.chats.chats[chatId]!!.memberCount, "the planned total, above the two rows written")
            val retried = h.createGroup(h.command(amina, setOf(brian, chidi), key = key)).data()

            assertEquals(chatId, retried.chat.chatId)
            assertEquals(
                setOf(amina, brian, chidi),
                h.chats
                    .members(chatId)
                    .map { it.userId }
                    .toSet(),
            )
            assertEquals(3, retried.chat.memberCount)
        }

    @Test
    fun `a retried create never puts back someone who left in between`() =
        runTest {
            val h = ChatHarness()
            h.connect(amina, listOf(brian, chidi))
            val key = UUID.randomUUID().toString()
            h.chats.partialJoins = 2

            assertFailsWith<IllegalStateException> { h.createGroup(h.command(amina, setOf(brian, chidi), key = key)) }
            val chatId =
                h.chats.chats.keys
                    .single()
            val written =
                h.chats
                    .members(chatId)
                    .map { it.userId }
                    .single { it != amina }
            h.leaveGroup(written, chatId).data()
            val retried = h.createGroup(h.command(amina, setOf(brian, chidi), key = key)).data()

            assertEquals(chatId, retried.chat.chatId)
            assertEquals(listOf(amina), h.chats.members(chatId).map { it.userId }, "nobody re-added, nobody new")
        }

    @Test
    fun `an add whose rows fail to write sets the count back to the rows`() =
        runTest {
            val h = ChatHarness()
            val chatId = h.group(amina, listOf(brian)).chat.chatId
            h.connect(amina, chidi)
            h.chats.failCommits = 1

            assertFailsWith<IllegalStateException> { h.addMembers(amina, chatId, setOf(chidi)) }

            assertEquals(2, h.chats.chats[chatId]!!.memberCount)
            assertEquals(2, h.chats.members(chatId).size)
        }

    @Test
    fun `ChatCreated has one id per chat, made from the chat's`() =
        runTest {
            val h = ChatHarness()
            h.connect(amina, brian)
            val direct =
                h
                    .openDirectChat(amina, brian)
                    .data()
                    .chat.chatId
            val group = h.group(amina, listOf(brian)).chat.chatId

            val ids =
                h.chats.commits
                    .flatMap { it.events }
                    .filter { it.type == ChatCreated.TYPE }
                    .map { it.eventId.value }

            val expected = listOf(direct, group).map { UUID.nameUUIDFromBytes("${ChatCreated.TYPE}:$it".toByteArray()) }
            assertEquals(expected, ids)
        }

    // ---- the members cache ----

    @Test
    fun `a change marks the cache, so a list read before it isn't put back`() =
        runTest {
            val h = ChatHarness()
            val chatId = h.group(amina, listOf(brian)).chat.chatId
            val before = h.membership.members(chatId)
            assertEquals(before, h.cache.entries[chatId])

            h.connect(amina, chidi)
            h.addMembers(amina, chatId, setOf(chidi)).data()
            h.cache.fillIfAbsent(chatId, before)

            assertNull(h.cache.entries[chatId], "the late fill of the old list was refused")
            assertTrue(chidi in h.membership.members(chatId))
            h.cache.expireMarkers()
            assertEquals(setOf(amina, brian, chidi), h.membership.members(chatId))
            assertEquals(setOf(amina, brian, chidi), h.cache.entries[chatId])
            assertTrue(dalia !in h.membership.members(chatId))
        }
}
