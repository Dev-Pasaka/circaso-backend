package com.circaso.messaging

import com.circaso.messaging.DeliveryHarness.Companion.cipher
import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.MessageKind
import com.circaso.messaging.api.MessageNewFrame
import com.circaso.messaging.api.ReceiptFrame
import com.circaso.messaging.api.ReceiptStatus
import com.circaso.messaging.domain.models.RecipientCopy
import com.circaso.messaging.domain.models.SendMessageCommand
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.messaging.presentation.dto.PhoneFrame
import com.circaso.shared.CircasoJson
import com.circaso.shared.ClientFrame
import com.circaso.shared.DeviceId
import com.circaso.shared.UserId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M12 and M13: sending (idempotency, reach, membership, fan-out) and reading and acknowledging the inbox. */
class DeliveryTest {
    private val h = DeliveryHarness()
    private val amina = UserId.new()
    private val brian = UserId.new()
    private val chidi = UserId.new()

    private fun command(
        from: UserId,
        chatId: ChatId,
        kind: MessageKind = MessageKind.MESSAGE,
        ciphertext: String? = null,
        recipients: List<RecipientCopy> = emptyList(),
        clientMessageId: UUID = UUID.randomUUID(),
    ) = SendMessageCommand(
        from,
        chatId,
        clientMessageId,
        kind,
        ciphertext,
        recipients,
        emptyList(),
        pushable = true,
    )

    // ---- sending ----

    @Test
    fun `a retry with the same clientMessageId returns the first message and sends nothing more`() =
        runBlocking<Unit> {
            val chat = h.direct(amina, brian)
            val clientId = UUID.randomUUID()

            val first = h.sendDirect(amina, brian, chat, clientMessageId = clientId)
            val again = h.sendDirect(amina, brian, chat, clientMessageId = clientId)
            h.settle()

            assertEquals(first, again)
            assertEquals(listOf(first.messageId), h.delivery.inboxOf(brian).map { it.messageId })
            assertEquals(1, h.live.of("message.new").size)
        }

    @Test
    fun `a direct send needs reach both ways`() =
        runBlocking<Unit> {
            val chat = h.direct(amina, brian)
            h.chats.reach.pairs
                .remove(brian to amina)

            val oneWay = h.sendMessage(command(amina, chat, recipients = listOf(RecipientCopy(brian, cipher()))))

            assertEquals("users.not_reachable", oneWay.errorCode())
            assertTrue(h.delivery.inboxOf(brian).isEmpty())
        }

    @Test
    fun `someone outside a chat can't send to it, and it looks like no chat at all`() =
        runBlocking<Unit> {
            val group = h.group(amina, listOf(brian))
            val direct = h.direct(amina, brian)

            assertEquals(
                "messaging.chat_not_found",
                h.sendMessage(command(chidi, group, ciphertext = cipher())).errorCode(),
            )
            val intoDirect = command(chidi, direct, recipients = listOf(RecipientCopy(brian, cipher())))
            assertEquals("messaging.chat_not_found", h.sendMessage(intoDirect).errorCode())
            val nowhere = command(amina, ChatId.new(), ciphertext = cipher())
            assertEquals("messaging.chat_not_found", h.sendMessage(nowhere).errorCode())
        }

    @Test
    fun `recipients must be the chat's other members`() =
        runBlocking<Unit> {
            val direct = h.direct(amina, brian)
            val group = h.group(amina, listOf(brian))

            val wrongPerson = command(amina, direct, recipients = listOf(RecipientCopy(chidi, cipher())))
            val twice = command(amina, direct, recipients = List(2) { RecipientCopy(brian, cipher()) })
            val senderKeyOutside =
                command(amina, group, MessageKind.SENDER_KEY, recipients = listOf(RecipientCopy(chidi, cipher())))
            val senderKeyToSelf =
                command(amina, group, MessageKind.SENDER_KEY, recipients = listOf(RecipientCopy(amina, cipher())))

            for (refused in listOf(wrongPerson, twice, senderKeyOutside, senderKeyToSelf)) {
                assertEquals("messaging.recipients_mismatch", h.sendMessage(refused).errorCode())
            }
        }

    @Test
    fun `the ciphertext has to be where the chat's kind puts it`() =
        runBlocking<Unit> {
            val direct = h.direct(amina, brian)
            val group = h.group(amina, listOf(brian))

            assertEquals("request.malformed", h.sendMessage(command(amina, direct, ciphertext = cipher())).errorCode())
            assertEquals("request.malformed", h.sendMessage(command(amina, group)).errorCode())
            val both = command(amina, group, ciphertext = cipher(), recipients = listOf(RecipientCopy(brian, cipher())))
            assertEquals("request.malformed", h.sendMessage(both).errorCode())
        }

    @Test
    fun `ciphertext over 64 KB is refused`() =
        runBlocking<Unit> {
            val group = h.group(amina, listOf(brian))

            val tooLarge = h.sendMessage(command(amina, group, ciphertext = cipher(64 * 1024 + 1)))
            val atTheLimit = h.sendMessage(command(amina, group, ciphertext = cipher(64 * 1024)))

            assertEquals("messaging.message_too_large", tooLarge.errorCode())
            atTheLimit.data()
        }

    @Test
    fun `a direct message goes into the recipient's inbox with its ciphertext, then a pushable frame`() =
        runBlocking<Unit> {
            val chat = h.direct(amina, brian)
            val text = cipher()

            val sent = h.sendDirect(amina, brian, chat, text)
            assertTrue(h.live.sent.isEmpty(), "frames go out after the answer")
            h.settle()

            val row = h.delivery.inboxOf(brian).single()
            assertEquals(text, row.ciphertext)
            assertEquals(amina, row.senderId)
            assertTrue(h.delivery.inboxOf(amina).isEmpty())
            val frame = h.live.of("message.new").single()
            assertEquals(setOf(brian), frame.to)
            val data = frame.data<MessageNewFrame>()
            assertEquals(true, data.pushable)
            assertEquals(sent.messageId, data.messageId)
            assertEquals(text, data.ciphertext, "a small ciphertext rides in the frame")
            assertEquals(sent.messageId, h.chats.chats.lastMessageId(chat), "the chat list's last message")
        }

    @Test
    fun `a large ciphertext is left out of the frame and fetched from the inbox`() =
        runBlocking<Unit> {
            val chat = h.direct(amina, brian)

            h.sendDirect(amina, brian, chat, cipher(40 * 1024))
            h.settle()

            val frame = h.live.of("message.new").single()
            assertNull(frame.data<MessageNewFrame>().ciphertext)
            assertEquals(
                40 * 1024,
                Base64
                    .getDecoder()
                    .decode(
                        h.delivery
                            .inboxOf(brian)
                            .single()
                            .ciphertext,
                    ).size,
            )
        }

    @Test
    fun `a group message is one body and a pointer per member, written at once, with one frame for all`() =
        runBlocking<Unit> {
            val members = List(250) { UserId.new() }
            val group = h.group(amina, members)
            val text = cipher()

            val sent = h.sendGroup(amina, group, text)
            h.settle()

            assertEquals(text, h.delivery.bodies[group to sent.messageId]!!.ciphertext)
            assertEquals(1, h.delivery.writes, "the record, the body and 250 pointers in one write")
            assertEquals(listOf("messages.send"), h.chats.transactor.operations.filter { it.startsWith("messages") })
            for (member in members) {
                val pointer = h.delivery.inboxOf(member).single()
                assertTrue(pointer.groupBody)
                assertNull(pointer.ciphertext, "pointers hold no ciphertext")
            }
            assertEquals(250L, h.pending.counts[sent.messageId])
            val frame = h.live.of("message.new").single()
            assertEquals(members.toSet(), frame.to, "one deliverMany to everyone but the sender")
            assertEquals(text, frame.data<MessageNewFrame>().ciphertext)
        }

    @Test
    fun `sender keys go to chosen members, each their own copy, and wake nobody`() =
        runBlocking<Unit> {
            val group = h.group(amina, listOf(brian, chidi))
            val before = h.sendGroup(amina, group)

            val keys =
                command(
                    amina,
                    group,
                    MessageKind.SENDER_KEY,
                    recipients = listOf(RecipientCopy(brian, cipher(10)), RecipientCopy(chidi, cipher(20))),
                )
            h.sendMessage(keys).data()
            h.settle()

            assertEquals(
                cipher(10),
                h.delivery
                    .inboxOf(brian)
                    .last()
                    .ciphertext,
            )
            assertEquals(
                cipher(20),
                h.delivery
                    .inboxOf(chidi)
                    .last()
                    .ciphertext,
            )
            val frames = h.live.of("message.new").filter { it.data<MessageNewFrame>().kind == MessageKind.SENDER_KEY }
            assertEquals(listOf(setOf(brian), setOf(chidi)), frames.map { it.to })
            assertTrue(frames.none { it.data<MessageNewFrame>().pushable })
            assertEquals(before.messageId, h.chats.chats.lastMessageId(group), "not shown, so not last")
        }

    @Test
    fun `control messages never wake anyone, whatever the phone asked`() =
        runBlocking<Unit> {
            val group = h.group(amina, listOf(brian))

            h.sendGroup(amina, group, kind = MessageKind.CONTROL)
            h.settle()

            assertEquals(
                false,
                h.live
                    .of("message.new")
                    .single()
                    .data<MessageNewFrame>()
                    .pushable,
            )
        }

    // ---- the inbox ----

    @Test
    fun `the inbox pages oldest first and fills in group bodies`() =
        runBlocking<Unit> {
            val direct = h.direct(amina, brian)
            val group = h.group(amina, listOf(brian))
            val sent =
                listOf(
                    h.sendDirect(amina, brian, direct),
                    h.sendGroup(amina, group, "AAEC"),
                    h.sendDirect(amina, brian, direct),
                )

            val first = h.readInbox(brian, null, 2).data()
            val second = h.readInbox(brian, first.next, 2).data()

            assertEquals(sent.take(2).map { it.messageId }, first.messages.map { it.messageId })
            assertEquals("AAEC", first.messages[1].ciphertext)
            assertEquals(listOf(sent[2].messageId), second.messages.map { it.messageId })
            assertNull(second.next)
        }

    @Test
    fun `an ack deletes a direct copy and tells the sender at once`() =
        runBlocking<Unit> {
            val chat = h.direct(amina, brian)
            val sent = h.sendDirect(amina, brian, chat)

            val acked = h.ackDelivered(brian, listOf(sent.messageId, UUID.randomUUID())).data()

            assertEquals(1, acked, "an id not in the inbox is skipped")
            assertTrue(h.delivery.inboxOf(brian).isEmpty())
            val receipt = h.frames.to(amina).single { it.type == "receipt" }
            assertEquals(chat, receipt.data<ReceiptFrame>().chatId)
            val item = receipt.data<ReceiptFrame>().receipts.single()
            assertEquals(sent.messageId, item.messageId)
            assertEquals(ReceiptStatus.DELIVERED, item.status)
            assertEquals(brian, item.userId)
        }

    @Test
    fun `a group body stays until the last member acks, and a repeated ack counts once`() =
        runBlocking<Unit> {
            val group = h.group(amina, listOf(brian, chidi))
            val sent = h.sendGroup(amina, group)

            h.ackDelivered(brian, listOf(sent.messageId))
            h.ackDelivered(brian, listOf(sent.messageId))
            assertNotNull(h.delivery.bodies[group to sent.messageId], "Chidi hasn't got it yet")
            assertEquals(1L, h.pending.counts[sent.messageId])

            h.ackDelivered(chidi, listOf(sent.messageId))
            assertNull(h.delivery.bodies[group to sent.messageId])
            assertNull(h.pending.counts[sent.messageId])
        }

    @Test
    fun `a body whose counter Redis lost is left for its 30-day expiry`() =
        runBlocking<Unit> {
            val group = h.group(amina, listOf(brian, chidi))
            val sent = h.sendGroup(amina, group)
            h.pending.lose(sent.messageId)

            h.ackDelivered(brian, listOf(sent.messageId))

            assertNotNull(h.delivery.bodies[group to sent.messageId], "Chidi can still fetch it")
            assertTrue(h.delivery.inboxOf(brian).isEmpty())
        }

    @Test
    fun `group delivered receipts go to the sender in one frame per 5 seconds`() =
        runBlocking<Unit> {
            val group = h.group(amina, listOf(brian, chidi))
            val sent = h.sendGroup(amina, group)

            h.ackDelivered(brian, listOf(sent.messageId))
            h.ackDelivered(chidi, listOf(sent.messageId))
            h.passTime(Duration.ofSeconds(4))
            assertTrue(h.frames.to(amina).none { it.type == "receipt" }, "not before the window ends")

            h.passTime(Duration.ofSeconds(1))
            val receipt = h.frames.to(amina).single { it.type == "receipt" }
            val items = receipt.data<ReceiptFrame>().receipts
            assertEquals(setOf(brian, chidi), items.map { it.userId }.toSet())
            assertTrue(h.frames.sent.none { it.to != amina && it.type == "receipt" }, "the sender only")
        }

    @Test
    fun `a second receipt batch carries only what came after the first`() =
        runBlocking<Unit> {
            val group = h.group(amina, listOf(brian, chidi))
            val sent = h.sendGroup(amina, group)

            h.ackDelivered(brian, listOf(sent.messageId))
            h.passTime(Duration.ofSeconds(5))
            h.ackDelivered(chidi, listOf(sent.messageId))
            h.passTime(Duration.ofSeconds(5))

            val batches =
                h.frames
                    .to(amina)
                    .filter { it.type == "receipt" }
                    .map { it.data<ReceiptFrame>().receipts }
            assertEquals(listOf(listOf(brian), listOf(chidi)), batches.map { batch -> batch.map { it.userId } })
        }

    @Test
    fun `two acks of the same direct message racing give one receipt`() =
        runBlocking<Unit> {
            val chat = h.direct(amina, brian)
            val sent = h.sendDirect(amina, brian, chat)
            val acks =
                listOf(
                    async { h.ackDelivered(brian, listOf(sent.messageId)).data() },
                    async { h.ackDelivered(brian, listOf(sent.messageId)).data() },
                ).awaitAll()

            assertEquals(1, acks.sum(), "one took the row, the other found it gone")
            assertEquals(1, h.frames.to(amina).count { it.type == "receipt" })
        }

    @Test
    fun `a pointer whose body is gone is left out of the inbox and deleted`() =
        runBlocking<Unit> {
            val group = h.group(amina, listOf(brian))
            val sent = h.sendGroup(amina, group)
            h.delivery.deleteGroupBody(group, sent.messageId)

            assertTrue(
                h
                    .readInbox(brian, null, 100)
                    .data()
                    .messages
                    .isEmpty(),
            )
            assertTrue(h.delivery.inboxOf(brian).isEmpty())
        }

    @Test
    fun `the socket's ack frame does what the inbox ack does`() =
        runBlocking<Unit> {
            val chat = h.direct(amina, brian)
            val sent = h.sendDirect(amina, brian, chat)

            val frame =
                ClientFrame(
                    "messaging",
                    "ack",
                    CircasoJson.encodeToJsonElement(
                        PhoneFrame.Ack.serializer(),
                        PhoneFrame.Ack(listOf(sent.messageId.toString())),
                    ),
                )
            h.inbound.receive(brian, DeviceId.new(), frame).data()

            assertTrue(h.delivery.inboxOf(brian).isEmpty())
        }

    @Test
    fun `a frame of an unknown type or with unreadable data is refused, not thrown`() =
        runBlocking<Unit> {
            val device = DeviceId.new()
            val unknown = ClientFrame("messaging", "presence")
            val unreadable = ClientFrame("messaging", "read")

            assertEquals("request.malformed", h.inbound.receive(amina, device, unknown).errorCode())
            assertEquals("request.malformed", h.inbound.receive(amina, device, unreadable).errorCode())
        }
}
