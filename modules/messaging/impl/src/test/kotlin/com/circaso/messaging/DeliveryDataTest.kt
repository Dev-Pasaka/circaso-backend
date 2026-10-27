package com.circaso.messaging

import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.messaging.PostgresHarness.Companion.RACE_RUNS
import com.circaso.messaging.PostgresHarness.Companion.concurrently
import com.circaso.messaging.api.MessageKind
import com.circaso.messaging.data.repositories.MessagingExpiry
import com.circaso.messaging.domain.models.Fanout
import com.circaso.messaging.domain.models.MessagingSettings
import com.circaso.messaging.domain.repositories.DeliveryRepository
import com.circaso.messaging.domain.usecases.UpdateSettings
import com.circaso.shared.UserId
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sending and delivery on PostgreSQL: one transaction per send (claim, record, body, every inbox row), acks taken with
 * `DELETE … RETURNING`, the group body gone with the last ack, the inbox page with its bodies joined in, and the purge
 * with its partitions.
 */
class DeliveryDataTest {
    private val h = PostgresHarness()

    @BeforeTest
    fun clean() = TestPostgres.truncate("messaging")

    private fun inboxRows(userId: UserId) =
        h.count("SELECT count(*) FROM messaging.inbox WHERE user_id = ?", userId.value)

    private fun bodies(messageId: UUID) =
        h.count("SELECT count(*) FROM messaging.group_messages WHERE message_id = ?", messageId)

    // ---- M12 ----

    @Test
    fun `a group send writes one record, one body and a pointer per member in one transaction`() =
        runBlocking<Unit> {
            val owner = UserId.new()
            val members = List(20) { UserId.new() }
            val group = h.group(owner, members)

            val sent = h.sendMessage(h.groupCommand(owner, group)).data()

            assertEquals(
                1,
                h.count("SELECT count(*) FROM messaging.message_records WHERE message_id = ?", sent.messageId),
            )
            assertEquals(1, bodies(sent.messageId))
            assertEquals(20, h.count("SELECT count(*) FROM messaging.inbox WHERE message_id = ?", sent.messageId))
            assertEquals(0, h.count("SELECT count(*) FROM messaging.inbox WHERE ciphertext IS NOT NULL"))
            assertEquals(20L, h.pending.counts[sent.messageId])
        }

    @Test
    fun `of many sends with one clientMessageId at once, one is written and all answer its id (50 runs)`() =
        runBlocking<Unit> {
            repeat(RACE_RUNS) { run ->
                val (amina, brian) = List(2) { UserId.new() }
                val chat = h.direct(amina, brian)
                val clientMessageId = UUID.randomUUID()

                val sent =
                    concurrently(8) { h.sendMessage(h.directCommand(amina, brian, chat, clientMessageId)).data() }

                assertEquals(1, sent.map { it.messageId }.toSet().size, "one message id, run $run")
                assertEquals(1, inboxRows(brian), "run $run")
                assertEquals(1, h.count("SELECT count(*) FROM messaging.message_records WHERE chat_id = ?", chat.value))
                assertEquals(
                    1,
                    h.count(
                        "SELECT count(*) FROM messaging.client_messages WHERE client_message_id = ?",
                        clientMessageId,
                    ),
                )
            }
        }

    @Test
    fun `a send whose transaction fails leaves no claim and no rows, and its retry sends it once`() =
        runBlocking<Unit> {
            val failing = PostgresHarness(delivery = { FailingWrites(it) })
            val (amina, brian) = List(2) { UserId.new() }
            val chat = failing.direct(amina, brian)
            val command = failing.directCommand(amina, brian, chat)

            assertFailsWith<IllegalStateException> { failing.sendMessage(command) }

            assertEquals(0, h.count("SELECT count(*) FROM messaging.client_messages"))
            assertEquals(0, h.count("SELECT count(*) FROM messaging.message_records"))
            assertEquals(0, inboxRows(brian))
            val retried = h.sendMessage(command).data()
            assertEquals(retried.messageId, h.sendMessage(command).data().messageId, "and a repeat answers it")
            assertEquals(1, inboxRows(brian))
        }

    @Test
    fun `an expired clientMessageId is taken over by a new send`() =
        runBlocking<Unit> {
            val (amina, brian) = List(2) { UserId.new() }
            val clientMessageId = UUID.randomUUID()
            val first = UUID.randomUUID()
            val past = h.clock.instant().minusSeconds(10)

            assertNull(h.delivery.claimClientMessage(amina, clientMessageId, first, past))
            val next = UUID.randomUUID()
            assertNull(h.delivery.claimClientMessage(amina, clientMessageId, next, h.clock.instant().plusSeconds(60)))
            assertEquals(next, h.delivery.claimClientMessage(amina, clientMessageId, UUID.randomUUID(), past))
            assertEquals(1, h.count("SELECT count(*) FROM messaging.client_messages"))
        }

    // ---- acks ----

    @Test
    fun `of many acks of one inbox row at once, exactly one takes it and one receipt goes (50 runs)`() =
        runBlocking<Unit> {
            repeat(RACE_RUNS) { run ->
                val (amina, brian) = List(2) { UserId.new() }
                val chat = h.direct(amina, brian)
                val sent = h.sendMessage(h.directCommand(amina, brian, chat)).data()
                val receiptsBefore = h.frames.count

                val acked = concurrently(10) { h.ackDelivered(brian, listOf(sent.messageId)).data() }

                assertEquals(1, acked.sum(), "run $run")
                assertEquals(0, inboxRows(brian))
                assertEquals(receiptsBefore + 1, h.frames.count, "one delivered receipt to Amina, run $run")
            }
        }

    @Test
    fun `of a group's members all acking at once, the last ack deletes the body, once (50 runs)`() =
        runBlocking<Unit> {
            repeat(RACE_RUNS) { run ->
                val owner = UserId.new()
                val members = List(12) { UserId.new() }
                val group = h.group(owner, members)
                val sent = h.sendMessage(h.groupCommand(owner, group)).data()

                concurrently(members.size - 2) { i -> h.ackDelivered(members[i], listOf(sent.messageId)).data() }
                assertEquals(1, bodies(sent.messageId), "two members haven't got it yet, run $run")

                val last = members.takeLast(2)
                val acked = concurrently(6) { i -> h.ackDelivered(last[i % 2], listOf(sent.messageId)).data() }

                assertEquals(2, acked.sum(), "each of the last two counted once, run $run")
                assertEquals(0, bodies(sent.messageId), "run $run")
                assertNull(h.pending.counts[sent.messageId])
                assertEquals(0, h.count("SELECT count(*) FROM messaging.inbox WHERE message_id = ?", sent.messageId))
            }
        }

    // ---- M13, M14, DeviceReplaced ----

    @Test
    fun `the inbox pages oldest first by keyset, each pointer with its body joined in`() =
        runBlocking<Unit> {
            val (amina, brian) = List(2) { UserId.new() }
            val direct = h.direct(amina, brian)
            val group = h.group(amina, listOf(brian))
            val first = h.sendMessage(h.directCommand(amina, brian, direct)).data()
            val second = h.sendMessage(h.groupCommand(amina, group)).data()
            val third = h.sendMessage(h.groupCommand(amina, group, MessageKind.CONTROL)).data()

            val page = h.readInbox(brian, null, 2).data()
            val rest = h.readInbox(brian, page.next, 2).data()

            assertEquals(listOf(first.messageId, second.messageId), page.messages.map { it.messageId })
            assertEquals(listOf(third.messageId), rest.messages.map { it.messageId })
            assertNull(rest.next)
            assertTrue((page.messages + rest.messages).all { it.ciphertext == PostgresHarness.cipher() })
            assertTrue(page.messages[1].groupBody)
        }

    @Test
    fun `a pointer whose body is gone is left out and deleted`() =
        runBlocking<Unit> {
            val (amina, brian) = List(2) { UserId.new() }
            val group = h.group(amina, listOf(brian))
            val sent = h.sendMessage(h.groupCommand(amina, group)).data()
            h.delivery.deleteGroupBody(group, sent.messageId)

            assertTrue(
                h
                    .readInbox(brian, null, 10)
                    .data()
                    .messages
                    .isEmpty(),
            )
            assertEquals(0, inboxRows(brian))
        }

    @Test
    fun `delete for everyone drops the copies and the body in one transaction, and keeps the record`() =
        runBlocking<Unit> {
            val owner = UserId.new()
            val members = List(5) { UserId.new() }
            val group = h.group(owner, members)
            val sent = h.sendMessage(h.groupCommand(owner, group)).data()

            h.deleteForEveryone(owner, group, sent.messageId).data()

            assertEquals(0, h.count("SELECT count(*) FROM messaging.inbox WHERE message_id = ?", sent.messageId))
            assertEquals(0, bodies(sent.messageId))
            assertNull(h.pending.counts[sent.messageId])
            assertNotNull(h.delivery.messageRecord(sent.messageId), "the record stays for receipts and repeats")
        }

    @Test
    fun `a replaced phone's waiting messages are taken a page at a time, the newer ones kept`() =
        runBlocking<Unit> {
            val (amina, brian) = List(2) { UserId.new() }
            val chat = h.direct(amina, brian)
            repeat(3) { h.sendMessage(h.directCommand(amina, brian, chat)).data() }
            Thread.sleep(10)
            val replacedAt = h.clock.instant()
            Thread.sleep(10)
            val after = h.sendMessage(h.directCommand(amina, brian, chat)).data()

            val first = h.delivery.takeInboxRowsSentBy(brian, replacedAt, 2)
            val second = h.delivery.takeInboxRowsSentBy(brian, replacedAt, 2)

            assertEquals(2, first.size)
            assertEquals(1, second.size)
            assertTrue((first + second).all { it.sentAt <= replacedAt })
            assertEquals(
                listOf(after.messageId),
                h
                    .readInbox(brian, null, 10)
                    .data()
                    .messages
                    .map { it.messageId },
            )
        }

    // ---- M22 ----

    @Test
    fun `two settings changes at once, one to each field, both stick (50 runs)`() =
        runBlocking<Unit> {
            val updateSettings = UpdateSettings(h.settings)
            repeat(RACE_RUNS) { run ->
                val amina = UserId.new()

                concurrently(2) { i ->
                    if (i == 0) updateSettings(amina, false, null).data() else updateSettings(amina, null, false).data()
                }

                assertEquals(
                    MessagingSettings(readReceipts = false, typing = false),
                    h.settings.settings(amina),
                    "run $run",
                )
            }
        }

    @Test
    fun `a settings change returns the row as written, and a field left out keeps its value`() =
        runBlocking<Unit> {
            val amina = UserId.new()

            assertEquals(MessagingSettings(true, false), h.settings.update(amina, null, false))
            assertEquals(MessagingSettings(false, false), h.settings.update(amina, false, null))
            assertEquals(MessagingSettings(false, true), h.settings.update(amina, null, true))
            assertEquals(MessagingSettings(), h.settings.settings(UserId.new()), "no row: both on")
        }

    // ---- expiry ----

    @Test
    fun `the purge deletes expired client ids and keeps every partition a day ahead`() =
        runBlocking<Unit> {
            val (amina, brian) = List(2) { UserId.new() }
            h.delivery.claimClientMessage(
                amina,
                UUID.randomUUID(),
                UUID.randomUUID(),
                h.clock.instant().minusSeconds(1),
            )
            h.delivery.claimClientMessage(
                brian,
                UUID.randomUUID(),
                UUID.randomUUID(),
                h.clock.instant().plusSeconds(60),
            )

            MessagingExpiry(h.database).run()

            assertEquals(1, h.count("SELECT count(*) FROM messaging.client_messages"))
            val tomorrow =
                LocalDate
                    .now(ZoneOffset.UTC)
                    .plusDays(1)
                    .toString()
                    .replace("-", "")
            for (table in listOf("inbox", "group_messages", "message_records", "realtime", "outbox")) {
                val partition = "${table}_p$tomorrow"
                assertEquals(
                    1,
                    h.count(
                        "SELECT count(*) FROM pg_tables WHERE schemaname = 'messaging' AND tablename = ?",
                        partition,
                    ),
                    partition,
                )
            }
        }

    @Test
    fun `message ids land in their own day's partition`() =
        runBlocking<Unit> {
            val (amina, brian) = List(2) { UserId.new() }
            val chat = h.direct(amina, brian)
            h.sendMessage(h.directCommand(amina, brian, chat)).data()

            val today = LocalDate.now(ZoneOffset.UTC).toString().replace("-", "")
            assertEquals(1, h.count("SELECT count(*) FROM messaging.\"inbox_p$today\""))
            assertEquals(1, h.count("SELECT count(*) FROM messaging.\"message_records_p$today\""))
        }
}

/** Delivery whose send fails after its rows went in, inside the send's transaction, so it rolls back. */
class FailingWrites(private val delivery: DeliveryRepository) : DeliveryRepository by delivery {
    override suspend fun writeMessage(fanout: Fanout) {
        delivery.writeMessage(fanout)
        error("the send failed after writing")
    }
}
