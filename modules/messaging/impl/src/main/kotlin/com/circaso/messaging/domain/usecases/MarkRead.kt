package com.circaso.messaging.domain.usecases

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.MessageKind
import com.circaso.messaging.api.ReceiptStatus
import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.ChatKind
import com.circaso.messaging.domain.models.MessageRecord
import com.circaso.messaging.domain.models.Receipt
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.repositories.SettingsRepository
import com.circaso.messaging.domain.support.MessageSenders
import com.circaso.messaging.domain.support.ReceiptBatcher
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Clock
import java.util.UUID

/**
 * `messaging.read` from the phone: the user has read [ChatId] up to a message. The member's `read_up_to` only moves
 * forward (a conditional update), and only a call that moved it sends a receipt. The message's sender gets
 * `receipt read`, unless the reader turned read receipts off (M22): at once in a direct chat, batched every 5 seconds
 * in a group. Only for a `MESSAGE` sent in this chat by someone else, found from its `message_records` row whether or
 * not every member has it (decision 2026-10-27); reading up to your own message, a sender key or an id from another
 * chat moves the marker and tells nobody.
 */
class MarkRead(
    private val chats: ChatRepository,
    private val settings: SettingsRepository,
    private val senders: MessageSenders,
    private val receipts: ReceiptBatcher,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        reader: UserId,
        chatId: ChatId,
        upTo: UUID,
    ): Resource<Unit> {
        val line = chats.userChat(reader, chatId) ?: return Resource.error(MessagingError.ChatNotFound)
        if (!chats.moveReadMarker(reader, chatId, upTo)) return Resource.success(Unit)
        if (!settings.settings(reader).readReceipts) return Resource.success(Unit)

        val message = senders.recordIn(chatId, upTo) ?: return Resource.success(Unit)
        if (!isReceiptWorthy(message, reader)) return Resource.success(Unit)
        if (line.kind == ChatKind.DIRECT) {
            val read = Receipt(upTo, reader, ReceiptStatus.READ, clock.instant())
            receipts.direct(message.senderId, chatId, listOf(read))
        } else {
            receipts.group(message.senderId, chatId, upTo, reader, ReceiptStatus.READ)
        }
        return Resource.success(Unit)
    }

    private fun isReceiptWorthy(message: MessageRecord, reader: UserId) =
        message.kind == MessageKind.MESSAGE && message.senderId != reader
}
