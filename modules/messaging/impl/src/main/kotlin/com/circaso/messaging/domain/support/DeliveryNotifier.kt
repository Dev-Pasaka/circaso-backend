package com.circaso.messaging.domain.support

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.KeysChangedFrame
import com.circaso.messaging.api.MessageDeletedFrame
import com.circaso.messaging.api.MessageNewFrame
import com.circaso.messaging.api.MessageUndeliverableFrame
import com.circaso.messaging.api.ReceiptFrame
import com.circaso.messaging.api.ReceiptItem
import com.circaso.messaging.api.TypingFrame
import com.circaso.messaging.domain.models.InboxMessage
import com.circaso.messaging.domain.models.Receipt
import com.circaso.messaging.domain.ports.LiveFrames
import com.circaso.messaging.domain.ports.RealtimeFrames
import com.circaso.shared.UserId
import java.util.UUID

/**
 * The delivery frames (Messaging → Socket frames). `message.new` and `typing` are not replayed and go through
 * [LiveFrames] (`deliverMany`, one publish); `message.deleted`, `message.undeliverable`, `receipt` and `keys.changed`
 * are replayed, so each recipient gets their own `seq` through [RealtimeFrames]. No frame carries text from a chat:
 * only ids and, when it fits, the ciphertext the recipient alone can open.
 */
class DeliveryNotifier(
    private val live: LiveFrames,
    private val frames: RealtimeFrames,
    private val config: MessagingConfig,
) {
    /**
     * [message] is one recipient's row, or a group's pointer with the group ciphertext filled in: the same frame
     * goes to every one of [recipients]. The gateway pushes the offline ones when [pushable] (Frame data).
     */
    suspend fun newMessage(
        recipients: Collection<UserId>,
        message: InboxMessage,
        pushable: Boolean,
    ) {
        if (recipients.isEmpty()) return
        val fits = message.ciphertext?.let { it.length <= config.frameCiphertextMaxChars } ?: false
        val frame =
            MessageNewFrame(
                messageId = message.messageId,
                chatId = message.chatId,
                senderId = message.senderId,
                kind = message.kind,
                pushable = pushable,
                sentAt = message.sentAt,
                mediaIds = message.mediaIds,
                ciphertext = message.ciphertext.takeIf { fits },
            )
        live.send(recipients, frame)
    }

    suspend fun typing(
        recipients: Collection<UserId>,
        chatId: ChatId,
        userId: UserId,
        on: Boolean,
    ) = live.send(recipients, TypingFrame(chatId, userId, on))

    /** Receipts go to the sender only (decision 2026-10-25), several of one chat in one frame. */
    suspend fun receipts(
        senderId: UserId,
        chatId: ChatId,
        receipts: List<Receipt>,
    ) {
        if (receipts.isEmpty()) return
        val items = receipts.map { ReceiptItem(it.messageId, it.userId, it.status, it.at) }
        frames.send(listOf(senderId), ReceiptFrame(chatId, items))
    }

    suspend fun messageDeleted(
        recipients: Collection<UserId>,
        chatId: ChatId,
        messageId: UUID,
    ) = frames.send(recipients, MessageDeletedFrame(chatId, messageId))

    /** The sender resends from its own copy once the recipient's new phone has published keys. */
    suspend fun undeliverable(
        senderId: UserId,
        chatId: ChatId,
        messageId: UUID,
        recipientId: UserId,
    ) = frames.send(listOf(senderId), MessageUndeliverableFrame(chatId, messageId, recipientId))

    suspend fun keysChanged(recipients: Collection<UserId>, userId: UserId) =
        frames.send(recipients, KeysChangedFrame(userId))
}
