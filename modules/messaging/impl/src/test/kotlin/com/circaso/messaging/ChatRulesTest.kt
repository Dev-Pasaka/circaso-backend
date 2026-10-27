package com.circaso.messaging

import com.circaso.messaging.api.ChatCreated
import com.circaso.messaging.domain.models.Role
import com.circaso.shared.UserId
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The membership rules MS3's race tests covered that still mean something with transactions: retried creates,
 * succession ties, admins removing admins and one `ChatCreated` per chat. Through the use cases, over the fakes.
 */
class ChatRulesTest {
    private val amina = UserId.new()
    private val brian = UserId.new()
    private val chidi = UserId.new()

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
            assertEquals(
                setOf(amina, brian),
                h.chats
                    .members(first.chat.chatId)
                    .map { it.userId }
                    .toSet(),
            )
            assertEquals(2, h.chats.chats[first.chat.chatId]!!.memberCount, "the create committed whole, once")
            assertEquals(1, h.outbox.types().count { it == ChatCreated.TYPE })
            assertTrue(h.frames.sent.isEmpty(), "the retry adds nobody and tells nobody")
        }

    @Test
    fun `a retried create never puts back someone who left`() =
        runTest {
            val h = ChatHarness()
            h.connect(amina, listOf(brian, chidi))
            val key = UUID.randomUUID().toString()
            val chatId =
                h
                    .createGroup(h.command(amina, setOf(brian, chidi), key = key))
                    .data()
                    .chat.chatId
            h.leaveGroup(brian, chatId).data()

            val retried = h.createGroup(h.command(amina, setOf(brian, chidi), key = key)).data()

            assertEquals(chatId, retried.chat.chatId)
            assertEquals(
                setOf(amina, chidi),
                h.chats
                    .members(chatId)
                    .map { it.userId }
                    .toSet(),
            )
        }

    @Test
    fun `a retried create by a creator who has left answers chat_not_found`() =
        runTest {
            val h = ChatHarness()
            h.connect(amina, listOf(brian))
            val key = UUID.randomUUID().toString()
            val chatId =
                h
                    .createGroup(h.command(amina, setOf(brian), key = key))
                    .data()
                    .chat.chatId
            h.leaveGroup(amina, chatId).data()

            val retried = h.createGroup(h.command(amina, setOf(brian), key = key))

            assertEquals("messaging.chat_not_found", retried.errorCode())
            assertEquals(listOf(brian), h.chats.members(chatId).map { it.userId }, "and nothing was written")
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
                h.outbox.written
                    .filter { it.type == ChatCreated.TYPE }
                    .map { it.eventId.value }

            val expected = listOf(direct, group).map { UUID.nameUUIDFromBytes("${ChatCreated.TYPE}:$it".toByteArray()) }
            assertEquals(expected, ids)
        }
}
