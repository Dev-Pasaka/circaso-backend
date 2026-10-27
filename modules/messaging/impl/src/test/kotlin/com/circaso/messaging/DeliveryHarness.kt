package com.circaso.messaging

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.MessageKind
import com.circaso.messaging.domain.models.RecipientCopy
import com.circaso.messaging.domain.models.SendMessageCommand
import com.circaso.messaging.domain.models.SentMessage
import com.circaso.messaging.domain.support.DeliveryNotifier
import com.circaso.messaging.domain.support.InboxRemoval
import com.circaso.messaging.domain.support.MessageSenders
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.messaging.domain.support.OrderedIds
import com.circaso.messaging.domain.support.ReceiptBatcher
import com.circaso.messaging.domain.usecases.AckDelivered
import com.circaso.messaging.domain.usecases.DeleteForEveryone
import com.circaso.messaging.domain.usecases.MarkRead
import com.circaso.messaging.domain.usecases.ReadInbox
import com.circaso.messaging.domain.usecases.RelayTyping
import com.circaso.messaging.domain.usecases.RetireReplacedPhone
import com.circaso.messaging.domain.usecases.SendMessage
import com.circaso.messaging.domain.usecases.UpdateSettings
import com.circaso.messaging.presentation.api.MessagingRealtimeInbound
import com.circaso.messaging.presentation.services.PhoneFrameService
import com.circaso.shared.UserId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import java.time.Duration
import java.util.Base64
import java.util.UUID

/**
 * Sending and delivery wired over the in-memory fakes, on top of [ChatHarness]'s chats. Work that runs after an
 * answer (frames after a send, receipt batches) runs on [background], a test scope with virtual time: it runs when a
 * test calls [settle] or [passTime].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeliveryHarness(val config: MessagingConfig = MessagingConfig()) {
    val chats = ChatHarness(config)
    val delivery = MemoryDelivery()
    val pending = MemoryPending()
    val receipts = MemoryReceipts()
    val typing = MemoryTyping()
    val live = RecordingLiveFrames()
    val frames get() = chats.frames
    val background = TestScope()

    private val notifier = DeliveryNotifier(live, chats.frames, config)
    private val batcher = ReceiptBatcher(receipts, notifier, background, chats.clock, config)
    private val removal = InboxRemoval(delivery, pending)
    private val senders = MessageSenders(delivery)

    val sendMessage =
        SendMessage(
            chats.chats,
            chats.membership,
            chats.reach,
            delivery,
            delivery,
            pending,
            notifier,
            OrderedIds(chats.clock),
            background,
            config,
            chats.clock,
        )
    val readInbox = ReadInbox(delivery, config)
    val ackDelivered = AckDelivered(delivery, removal, batcher, chats.clock)
    val markRead = MarkRead(chats.chats, delivery, delivery, senders, batcher, chats.clock)
    val relayTyping = RelayTyping(chats.membership, delivery, typing, notifier, config)
    val deleteForEveryone =
        DeleteForEveryone(
            chats.chats,
            chats.membership,
            delivery,
            pending,
            senders,
            notifier,
            config,
            chats.clock,
        )
    val updateSettings = UpdateSettings(delivery)
    val retireReplacedPhone =
        RetireReplacedPhone(chats.keys, delivery, removal, chats.chats, chats.membership, notifier)
    val inbound =
        MessagingRealtimeInbound(PhoneFrameService(ackDelivered, markRead, relayTyping), config.inboxPageMax)

    /** Runs what is due in the background now (frames after a send, a receipt batch whose window has passed). */
    fun settle() = background.runCurrent()

    /** Moves the background's clock on by [by], running what falls due. */
    fun passTime(by: Duration) {
        background.advanceTimeBy(by.toMillis())
        background.runCurrent()
    }

    /** A direct chat between two people who can reach each other both ways. */
    suspend fun direct(a: UserId, b: UserId): ChatId {
        chats.connect(a, b)
        chats.connect(b, a)
        return chats
            .openDirectChat(a, b)
            .data()
            .chat.chatId
    }

    suspend fun group(owner: UserId, members: List<UserId>): ChatId = chats.group(owner, members).chat.chatId

    suspend fun sendDirect(
        from: UserId,
        to: UserId,
        chatId: ChatId,
        text: String = cipher(),
        clientMessageId: UUID = UUID.randomUUID(),
    ): SentMessage =
        sendMessage(
            SendMessageCommand(
                from,
                chatId,
                clientMessageId,
                MessageKind.MESSAGE,
                null,
                listOf(RecipientCopy(to, text)),
                emptyList(),
                pushable = true,
            ),
        ).data()

    suspend fun sendGroup(
        from: UserId,
        chatId: ChatId,
        text: String = cipher(),
        kind: MessageKind = MessageKind.MESSAGE,
    ): SentMessage =
        sendMessage(
            SendMessageCommand(
                from,
                chatId,
                UUID.randomUUID(),
                kind,
                text,
                emptyList(),
                emptyList(),
                pushable = true,
            ),
        ).data()

    companion object {
        /** Base64 of [bytes] random-looking bytes: what a phone would send. */
        fun cipher(bytes: Int = 48): String = Base64.getEncoder().encodeToString(ByteArray(bytes) { (it * 7).toByte() })
    }
}
