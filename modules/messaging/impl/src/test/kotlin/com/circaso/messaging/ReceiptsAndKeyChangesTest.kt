package com.circaso.messaging

import com.circaso.messaging.api.KeysChangedFrame
import com.circaso.messaging.api.MessageDeletedFrame
import com.circaso.messaging.api.MessageKind
import com.circaso.messaging.api.MessageUndeliverableFrame
import com.circaso.messaging.api.ReceiptFrame
import com.circaso.messaging.api.ReceiptStatus
import com.circaso.messaging.api.TypingFrame
import com.circaso.messaging.domain.models.KeyBundle
import com.circaso.messaging.domain.models.MessagingSettings
import com.circaso.messaging.domain.models.OneTimePreKey
import com.circaso.messaging.domain.models.RecipientCopy
import com.circaso.messaging.domain.models.SendMessageCommand
import com.circaso.messaging.domain.models.SignedPreKey
import com.circaso.shared.DeviceId
import com.circaso.shared.UserId
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Read receipts, typing, delete for everyone (M14), settings (M22), a replaced phone (`identity.DeviceReplaced`) and
 * the replay of the delivery frames that are kept.
 */
class ReceiptsAndKeyChangesTest {
    private val h = DeliveryHarness()
    private val amina = UserId.new()
    private val brian = UserId.new()
    private val chidi = UserId.new()

    private fun RecordingFrames.Sent.receipts() = data<ReceiptFrame>().receipts

    // ---- read receipts ----

    @Test
    fun `reading a direct chat moves the marker and tells the other person at once`() =
        runBlocking<Unit> {
            val chat = h.direct(amina, brian)
            val sent = h.sendDirect(amina, brian, chat)

            h.markRead(brian, chat, sent.messageId).data()

            assertEquals(sent.messageId, h.chats.chats.readMarks[brian to chat])
            val receipt =
                h.frames
                    .to(amina)
                    .single { it.type == "receipt" }
                    .receipts()
                    .single()
            assertEquals(ReceiptStatus.READ, receipt.status)
            assertEquals(sent.messageId, receipt.messageId)
        }

    @Test
    fun `the read marker only moves forward`() =
        runBlocking<Unit> {
            val chat = h.direct(amina, brian)
            val first = h.sendDirect(amina, brian, chat)
            val second = h.sendDirect(amina, brian, chat)
            h.markRead(brian, chat, second.messageId)

            h.markRead(brian, chat, first.messageId)

            assertEquals(second.messageId, h.chats.chats.readMarks[brian to chat])
            assertEquals(1, h.frames.to(amina).count { it.type == "receipt" })
        }

    @Test
    fun `with read receipts off the marker moves but nobody is told`() =
        runBlocking<Unit> {
            val chat = h.direct(amina, brian)
            val sent = h.sendDirect(amina, brian, chat)
            h.updateSettings(brian, readReceipts = false, typing = null).data()

            h.markRead(brian, chat, sent.messageId)

            assertEquals(sent.messageId, h.chats.chats.readMarks[brian to chat])
            assertTrue(h.frames.to(amina).none { it.type == "receipt" })
        }

    @Test
    fun `group read receipts go to the sender, batched`() =
        runBlocking<Unit> {
            val group = h.group(amina, listOf(brian, chidi))
            val sent = h.sendGroup(amina, group)

            h.markRead(brian, group, sent.messageId)
            h.markRead(chidi, group, sent.messageId)
            assertTrue(h.frames.sent.none { it.type == "receipt" })
            h.passTime(Duration.ofSeconds(5))

            val receipt = h.frames.sent.single { it.type == "receipt" }
            assertEquals(amina, receipt.to)
            assertEquals(setOf(ReceiptStatus.READ), receipt.receipts().map { it.status }.toSet())
            assertEquals(2, receipt.receipts().size)
        }

    @Test
    fun `a group read receipt reaches the sender after every member acked and the body is gone`() =
        runBlocking<Unit> {
            val group = h.group(amina, listOf(brian, chidi))
            val sent = h.sendGroup(amina, group)
            h.ackDelivered(brian, listOf(sent.messageId))
            h.ackDelivered(chidi, listOf(sent.messageId))
            assertNull(h.delivery.bodies[group to sent.messageId])
            h.passTime(Duration.ofSeconds(5))
            h.receipts.expireClaims()

            h.markRead(brian, group, sent.messageId)
            h.passTime(Duration.ofSeconds(5))

            val read =
                h.frames
                    .to(amina)
                    .filter { it.type == "receipt" }
                    .flatMap { it.receipts() }
            assertTrue(read.any { it.userId == brian && it.status == ReceiptStatus.READ })
        }

    @Test
    fun `a direct read receipt reaches the sender after the message was delivered and deleted`() =
        runBlocking<Unit> {
            val chat = h.direct(amina, brian)
            val sent = h.sendDirect(amina, brian, chat)
            h.ackDelivered(brian, listOf(sent.messageId))

            h.markRead(brian, chat, sent.messageId)

            val read = h.frames.to(amina).flatMap { if (it.type == "receipt") it.receipts() else emptyList() }
            assertTrue(read.any { it.status == ReceiptStatus.READ && it.messageId == sent.messageId })
        }

    @Test
    fun `reading up to your own message, a sender key or another chat's message tells nobody`() =
        runBlocking<Unit> {
            val chat = h.direct(amina, brian)
            val other = h.direct(amina, chidi)
            val group = h.group(amina, listOf(brian))
            val mine = h.sendDirect(brian, amina, chat)
            val elsewhere = h.sendDirect(amina, chidi, other)
            val keys =
                h
                    .sendMessage(
                        SendMessageCommand(
                            amina,
                            group,
                            UUID.randomUUID(),
                            MessageKind.SENDER_KEY,
                            null,
                            listOf(RecipientCopy(brian, DeliveryHarness.cipher())),
                            emptyList(),
                            pushable = false,
                        ),
                    ).data()

            h.markRead(brian, chat, mine.messageId).data()
            h.markRead(brian, chat, elsewhere.messageId).data()
            h.markRead(brian, group, keys.messageId).data()
            h.passTime(Duration.ofSeconds(5))

            assertTrue(h.frames.sent.none { it.type == "receipt" })
        }

    @Test
    fun `reading a chat you're not in is refused`() =
        runBlocking<Unit> {
            val chat = h.direct(amina, brian)
            val sent = h.sendDirect(amina, brian, chat)

            assertEquals("messaging.chat_not_found", h.markRead(chidi, chat, sent.messageId).errorCode())
        }

    // ---- typing ----

    @Test
    fun `typing is relayed to the other members once, until it stops`() =
        runBlocking<Unit> {
            val group = h.group(amina, listOf(brian, chidi))

            h.relayTyping(amina, group, true)
            h.relayTyping(amina, group, true)
            h.relayTyping(amina, group, false)

            val frames = h.live.of("typing")
            assertEquals(listOf(true, false), frames.map { it.data<TypingFrame>().on }, "the repeat was dropped")
            assertTrue(frames.all { it.to == setOf(brian, chidi) })
            assertTrue(h.frames.sent.none { it.type == "typing" }, "typing is never kept for replay")
        }

    @Test
    fun `typing is dropped in a group of more than 50, and when the sender turned it off`() =
        runBlocking<Unit> {
            val big = h.group(amina, List(50) { UserId.new() })
            val small = h.group(amina, List(49) { UserId.new() })

            h.relayTyping(amina, big, true).data()
            h.relayTyping(amina, small, true).data()
            assertEquals(1, h.live.of("typing").size, "51 people: dropped; 50: relayed")

            h.typing.expire()
            h.updateSettings(amina, readReceipts = null, typing = false)
            h.relayTyping(amina, small, true).data()
            assertEquals(1, h.live.of("typing").size)
        }

    @Test
    fun `typing into a chat you're not in is refused`() =
        runBlocking<Unit> {
            val chat = h.direct(amina, brian)

            assertEquals("messaging.chat_not_found", h.relayTyping(chidi, chat, true).errorCode())
        }

    // ---- delete for everyone ----

    @Test
    fun `the sender deletes a group message for everyone within 48 hours`() =
        runBlocking<Unit> {
            val group = h.group(amina, listOf(brian, chidi))
            val sent = h.sendGroup(amina, group)
            h.ackDelivered(brian, listOf(sent.messageId))
            h.chats.clock.advance(Duration.ofHours(47))

            h.deleteForEveryone(amina, group, sent.messageId).data()

            assertTrue(h.delivery.inboxOf(chidi).isEmpty(), "the undelivered copy is dropped")
            assertNull(h.delivery.bodies[group to sent.messageId])
            assertNull(h.pending.counts[sent.messageId])
            val deleted = h.frames.sent.filter { it.type == "message.deleted" }
            assertEquals(setOf(brian, chidi), deleted.map { it.to }.toSet())
            assertEquals(sent.messageId, deleted.first().data<MessageDeletedFrame>().messageId)
        }

    @Test
    fun `only the sender, and only within 48 hours, can delete for everyone`() =
        runBlocking<Unit> {
            val chat = h.direct(amina, brian)
            val sent = h.sendDirect(amina, brian, chat)

            assertEquals("messaging.delete_window_passed", h.deleteForEveryone(brian, chat, sent.messageId).errorCode())
            assertEquals("messaging.chat_not_found", h.deleteForEveryone(chidi, chat, sent.messageId).errorCode())
            h.chats.clock.advance(Duration.ofHours(49))
            assertEquals("messaging.delete_window_passed", h.deleteForEveryone(amina, chat, sent.messageId).errorCode())
            assertEquals(listOf(sent.messageId), h.delivery.inboxOf(brian).map { it.messageId })
        }

    @Test
    fun `after every copy is delivered the sender can still delete for everyone, and nobody else can`() =
        runBlocking<Unit> {
            val direct = h.direct(amina, brian)
            val group = h.group(amina, listOf(brian, chidi))
            val toBrian = h.sendDirect(amina, brian, direct)
            val toGroup = h.sendGroup(amina, group)
            h.ackDelivered(brian, listOf(toBrian.messageId, toGroup.messageId))
            h.ackDelivered(chidi, listOf(toGroup.messageId))

            assertEquals(
                "messaging.delete_window_passed",
                h.deleteForEveryone(brian, direct, toBrian.messageId).errorCode(),
            )
            assertEquals(
                "messaging.delete_window_passed",
                h.deleteForEveryone(chidi, group, toGroup.messageId).errorCode(),
            )
            h.deleteForEveryone(amina, direct, toBrian.messageId).data()
            h.deleteForEveryone(amina, group, toGroup.messageId).data()

            val deleted = h.frames.sent.filter { it.type == "message.deleted" }
            assertEquals(setOf(brian, chidi), deleted.map { it.to }.toSet())
            assertEquals(3, deleted.size, "Brian for both messages, Chidi for the group's")
        }

    @Test
    fun `a message id from another chat is not the caller's to delete there`() =
        runBlocking<Unit> {
            val direct = h.direct(amina, brian)
            val group = h.group(amina, listOf(brian))
            val sent = h.sendDirect(amina, brian, direct)

            assertEquals(
                "messaging.delete_window_passed",
                h.deleteForEveryone(amina, group, sent.messageId).errorCode(),
            )
        }

    @Test
    fun `a direct message still waiting is deleted for everyone`() =
        runBlocking<Unit> {
            val chat = h.direct(amina, brian)
            val sent = h.sendDirect(amina, brian, chat)

            h.deleteForEveryone(amina, chat, sent.messageId).data()

            assertTrue(h.delivery.inboxOf(brian).isEmpty())
            assertEquals(
                listOf(brian),
                h.frames.sent
                    .filter { it.type == "message.deleted" }
                    .map { it.to },
            )
        }

    // ---- settings ----

    @Test
    fun `settings start on, and a field left out stays as it is`() =
        runBlocking<Unit> {
            assertEquals(MessagingSettings(true, true), h.updateSettings(amina, null, null).data())
            assertEquals(MessagingSettings(false, true), h.updateSettings(amina, false, null).data())
            assertEquals(MessagingSettings(false, false), h.updateSettings(amina, null, false).data())
        }

    // ---- a replaced phone ----

    private fun bundle(userId: UserId, deviceId: DeviceId) =
        KeyBundle(userId, deviceId, "id-key", SignedPreKey(1, "spk", "sig"), h.chats.clock.instant())

    @Test
    fun `a replaced phone's waiting messages are dropped, senders told, and keys changed for everyone`() =
        runBlocking<Unit> {
            val oldPhone = DeviceId.new()
            h.chats.keys.publish(bundle(brian, oldPhone), listOf(OneTimePreKey(1, "otk")), h.chats.clock.instant())
            val direct = h.direct(amina, brian)
            val group = h.group(chidi, listOf(brian))
            val fromAmina = h.sendDirect(amina, brian, direct)
            val fromChidi = h.sendGroup(chidi, group)
            val replacedAt =
                h.chats.clock
                    .instant()
                    .plusSeconds(1)
            h.chats.clock.advance(Duration.ofMinutes(1))
            val afterwards = h.sendDirect(amina, brian, direct)

            h.retireReplacedPhone(brian, oldPhone, replacedAt).data()

            assertNull(h.chats.keys.bundles[brian])
            assertEquals(0, h.chats.keys.prekeyCount(brian))
            assertEquals(listOf(afterwards.messageId), h.delivery.inboxOf(brian).map { it.messageId })
            assertNull(h.delivery.bodies[group to fromChidi.messageId], "Brian was the last one waiting for it")
            val undeliverable = h.frames.sent.filter { it.type == "message.undeliverable" }
            assertEquals(
                setOf(amina to fromAmina.messageId, chidi to fromChidi.messageId),
                undeliverable.map { it.to to it.data<MessageUndeliverableFrame>().messageId }.toSet(),
            )
            assertTrue(undeliverable.all { it.data<MessageUndeliverableFrame>().recipientId == brian })
            val changed = h.frames.sent.filter { it.type == "keys.changed" }
            assertEquals(setOf(amina, chidi), changed.map { it.to }.toSet())
            assertTrue(changed.all { it.data<KeysChangedFrame>().userId == brian })
        }

    @Test
    fun `a bundle the new phone already published is kept`() =
        runBlocking<Unit> {
            val newPhone = DeviceId.new()
            h.chats.keys.publish(bundle(brian, newPhone), listOf(OneTimePreKey(1, "otk")), h.chats.clock.instant())

            h.retireReplacedPhone(brian, DeviceId.new(), h.chats.clock.instant()).data()

            assertEquals(
                newPhone,
                h.chats.keys.bundles[brian]!!
                    .deviceId,
            )
            assertEquals(1, h.chats.keys.prekeyCount(brian))
        }

    @Test
    fun `handling the event twice drops nothing more`() =
        runBlocking<Unit> {
            val chat = h.direct(amina, brian)
            h.sendDirect(amina, brian, chat)
            val at =
                h.chats.clock
                    .instant()
                    .plusSeconds(1)

            h.retireReplacedPhone(brian, DeviceId.new(), at)
            h.retireReplacedPhone(brian, DeviceId.new(), at)

            assertEquals(1, h.frames.sent.count { it.type == "message.undeliverable" })
        }

    // ---- replay ----

    @Test
    fun `receipts, deletes, undeliverables and key changes are replayed, new messages and typing are not`() =
        runBlocking<Unit> {
            val chat = h.direct(amina, brian)
            val first = h.sendDirect(amina, brian, chat)
            val second = h.sendDirect(amina, brian, chat)
            h.relayTyping(brian, chat, true)
            h.settle()
            h.ackDelivered(brian, listOf(first.messageId))
            h.deleteForEveryone(amina, chat, second.messageId)
            h.retireReplacedPhone(amina, DeviceId.new(), h.chats.clock.instant())

            val aminaMissed =
                h.chats
                    .replayFrames(amina, 0)
                    .data()!!
                    .map { it.json }
            val brianMissed =
                h.chats
                    .replayFrames(brian, 0)
                    .data()!!
                    .map { it.json }

            assertEquals(listOf("receipt"), aminaMissed)
            assertEquals(listOf("message.deleted", "keys.changed"), brianMissed)
            assertTrue(h.live.of("message.new").isNotEmpty() && h.live.of("typing").isNotEmpty())
        }
}
