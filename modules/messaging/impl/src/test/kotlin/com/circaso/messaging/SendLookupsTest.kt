package com.circaso.messaging

import com.circaso.messaging.DeliveryHarness.Companion.cipher
import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.MessageKind
import com.circaso.messaging.domain.models.Chat
import com.circaso.messaging.domain.models.RecipientCopy
import com.circaso.messaging.domain.models.SendMessageCommand
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.shared.UserId
import com.circaso.users.api.ReachPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M12's reads before the write: the chat row, the members and, for a direct message, reach either way run at once
 * rather than one after another, and still answer exactly as the one-at-a-time reads did.
 */
class SendLookupsTest {
    private val h = DeliveryHarness()
    private val amina = UserId.new()
    private val brian = UserId.new()

    private fun direct(
        chatId: ChatId,
        to: UserId = brian,
        ciphertext: String? = null,
    ) = SendMessageCommand(
        amina,
        chatId,
        UUID.randomUUID(),
        MessageKind.MESSAGE,
        ciphertext,
        listOf(RecipientCopy(to, cipher())),
        emptyList(),
        pushable = true,
    )

    /** The chat rows, with [chat] held until [release] completes, and [reading] completed once it is asked. */
    private class HeldChats(private val rows: ChatRepository) : ChatRepository by rows {
        val reading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun chat(chatId: ChatId): Chat? {
            reading.complete(Unit)
            withTimeout(TIMEOUT_MS) { release.await() }
            return rows.chat(chatId)
        }
    }

    /** Reach that waits for [ready] before it answers, counting its calls; [failing] throws instead of answering. */
    private class WatchedReach(private val reach: ReachPolicy, private val failing: Boolean = false) :
        ReachPolicy by reach {
        val calls = AtomicInteger()
        val bothAsked = CompletableDeferred<Unit>()
        var ready: CompletableDeferred<Unit>? = null

        override suspend fun canReach(from: UserId, to: UserId): Boolean {
            if (calls.incrementAndGet() == 2) bothAsked.complete(Unit)
            ready?.let { withTimeout(TIMEOUT_MS) { it.await() } }
            if (failing) error("users is down")
            return reach.canReach(from, to)
        }
    }

    @Test
    fun `a direct send asks reach both ways while the chat row is still being read`() =
        runBlocking<Unit> {
            val chat = h.direct(amina, brian)
            val rows = HeldChats(h.chats.chats)
            val reach = WatchedReach(h.chats.reach).apply { ready = rows.reading }
            // the chat row is only released once both reach reads have started: one after the other would time out
            reach.bothAsked.invokeOnCompletion { rows.release.complete(Unit) }

            val sent = h.sendMessageOver(rows, reach)(direct(chat)).data()

            assertEquals(2, reach.calls.get())
            assertEquals(listOf(sent.messageId), h.delivery.inboxOf(brian).map { it.messageId })
        }

    @Test
    fun `reach still has the last word on a direct send, once neither way is left`() =
        runBlocking<Unit> {
            val chat = h.direct(amina, brian)
            h.chats.reach.pairs
                .remove(brian to amina)
            assertNotNull(h.sendMessage(direct(chat)).data(), "amina still reaches brian")

            h.chats.reach.pairs
                .add(brian to amina)
            h.chats.reach.pairs
                .remove(amina to brian)
            assertNotNull(h.sendMessage(direct(chat)).data(), "brian still reaches amina")

            h.chats.reach.pairs
                .remove(brian to amina)
            assertEquals("users.not_reachable", h.sendMessage(direct(chat)).errorCode())
            assertEquals(2, h.delivery.inboxOf(brian).size)
        }

    @Test
    fun `a reach read the send turns out not to need never fails it`() =
        runBlocking<Unit> {
            // a group of exactly two looks like a direct chat until its row is read
            val group = h.group(amina, listOf(brian))
            val reach = WatchedReach(h.chats.reach, failing = true)

            val asDirect = h.sendMessageOver(reach = reach)(direct(group))

            assertEquals("request.malformed", asDirect.errorCode(), "the group's own rule, as before")
            assertTrue(h.delivery.inboxOf(brian).isEmpty())
        }

    @Test
    fun `a reach read the send needs fails it as it always did`() =
        runBlocking<Unit> {
            val chat = h.direct(amina, brian)
            val reach = WatchedReach(h.chats.reach, failing = true)

            assertFailsWith<IllegalStateException> { h.sendMessageOver(reach = reach)(direct(chat)) }
            assertTrue(h.delivery.inboxOf(brian).isEmpty())
        }

    @Test
    fun `no reach is read for someone outside the chat, or for a chat that isn't there`() =
        runBlocking<Unit> {
            val chat = h.direct(amina, brian)
            val reach = WatchedReach(h.chats.reach)
            val send = h.sendMessageOver(reach = reach)
            val outsider = UserId.new()

            val intruding = direct(chat).copy(sender = outsider)
            assertEquals("messaging.chat_not_found", send(intruding).errorCode())
            assertEquals("messaging.chat_not_found", send(direct(ChatId.new())).errorCode())
            assertEquals(0, reach.calls.get())
        }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
