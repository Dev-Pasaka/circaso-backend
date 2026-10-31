package com.circaso.messaging

import com.circaso.messaging.presentation.mappers.agentChatLimit
import com.circaso.messaging.presentation.mappers.toAgentResponse
import com.circaso.messaging.presentation.services.MessagingAgentService
import com.circaso.shared.UserId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Asha's chat list (decision 2026-10-31): the caller's own chats, capped, and never a sealed name or a message. */
class MessagingAgentTest {
    private val amina = UserId.new()
    private val brian = UserId.new()
    private val chidi = UserId.new()

    @Test
    fun `only the caller's chats, the caller left out of members, no sealed metadata`() =
        runTest {
            val h = ChatHarness()
            h.connect(amina, brian)
            val direct = h.openDirectChat(amina, brian).data()
            val group = h.group(amina, listOf(brian, chidi))
            val theirs = h.group(brian, listOf(chidi))
            val service = MessagingAgentService(h.listChats)

            val chats =
                service
                    .listChats(amina)
                    .data()
                    .toAgentResponse(amina, agentChatLimit(null))
                    .chats

            assertEquals(
                setOf(direct.chat.chatId.toString(), group.chat.chatId.toString()),
                chats.map { it.chatId }.toSet(),
            )
            assertFalse(chats.any { it.chatId == theirs.chat.chatId.toString() })
            assertTrue(chats.all { chat -> chat.members.none { it.userId == amina.toString() } })
            val inGroup = chats.single { it.kind == "GROUP" }
            assertEquals(3, inGroup.memberCount)
            assertTrue(inGroup.isAdmin)
            assertFalse(chats.toString().contains(h.sealed), "a group's sealed name never reaches the assistant")
        }

    @Test
    fun `the list is capped, 30 by default and 100 at most`() =
        runTest {
            assertEquals(30, agentChatLimit(null))
            assertEquals(100, agentChatLimit("5000"))
            assertEquals(1, agentChatLimit("0"))
            val h = ChatHarness()
            repeat(3) { h.group(amina, listOf(UserId.new())) }

            val chats =
                MessagingAgentService(h.listChats)
                    .listChats(amina)
                    .data()
                    .toAgentResponse(amina, 2)
                    .chats

            assertEquals(2, chats.size)
        }
}
