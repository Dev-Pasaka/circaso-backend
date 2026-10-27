package com.circaso.messaging

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.MessageKind
import com.circaso.messaging.domain.models.SendMessageCommand
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.shared.UserId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A send keeps its first message id however it is retried (decision 2026-10-27): a failure part-way, a caller that
 * goes away mid-write, and a retry racing the first attempt all end with one message per member under one id.
 */
class SendRetriesTest {
    private val h = DeliveryHarness(MessagingConfig(fanOutBatch = 2))
    private val amina = UserId.new()
    private val members = List(5) { UserId.new() }
    private val clientId = UUID.randomUUID()

    private fun send(chatId: ChatId) =
        SendMessageCommand(
            amina,
            chatId,
            clientId,
            MessageKind.MESSAGE,
            DeliveryHarness.cipher(),
            emptyList(),
            emptyList(),
            pushable = true,
        )

    /** Every member holds exactly one copy of [messageId], and nothing else. */
    private fun assertOneEach(messageId: UUID) {
        for (member in members) {
            assertEquals(listOf(messageId), h.delivery.inboxOf(member).map { it.messageId }, "$member")
        }
        assertEquals(messageId, h.delivery.records[messageId]!!.messageId)
    }

    @Test
    fun `a later batch fails, and the retry writes the same message under the same id`() =
        runBlocking<Unit> {
            val group = h.group(amina, members)
            h.delivery.failInboxWrites = 1
            h.delivery.writtenBeforeFailing = 2

            assertFailsWith<IllegalStateException> { h.sendMessage(send(group)) }
            val firstId = h.delivery.clientIds[amina to clientId]!!.messageId
            assertTrue(h.delivery.inboxOf(members.last()).isEmpty(), "the last batch never ran")

            val retried = h.sendMessage(send(group)).data()
            val again = h.sendMessage(send(group)).data()

            assertEquals(firstId, retried.messageId)
            assertEquals(retried, again)
            assertOneEach(firstId)
            assertTrue(h.delivery.clientIds[amina to clientId]!!.complete)
        }

    @Test
    fun `a caller that goes away mid-write still leaves the whole message written and the claim complete`() =
        runBlocking<Unit> {
            val group = h.group(amina, members)
            val gate = CompletableDeferred<Unit>()
            h.delivery.writeGate = gate

            val sending = launch { h.sendMessage(send(group)) }
            h.delivery.writesStarted.await()
            sending.cancel()
            gate.complete(Unit)
            sending.join()

            val claim = h.delivery.clientIds[amina to clientId]!!
            assertTrue(claim.complete)
            assertOneEach(claim.messageId)
            h.delivery.writeGate = null
            assertEquals(claim.messageId, h.sendMessage(send(group)).data().messageId)
        }

    @Test
    fun `a retry racing the first attempt writes the same rows and answers only once they exist`() =
        runBlocking<Unit> {
            val group = h.group(amina, members)
            val gate = CompletableDeferred<Unit>()
            h.delivery.writeGate = gate

            val first = async { h.sendMessage(send(group)).data() }
            h.delivery.writesStarted.await()
            h.delivery.writeGate = null
            val retried = h.sendMessage(send(group)).data()
            assertOneEach(retried.messageId)
            gate.complete(Unit)

            assertEquals(retried, first.await())
            assertOneEach(retried.messageId)
        }
}
