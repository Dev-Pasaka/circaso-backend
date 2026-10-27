package com.circaso.messaging.domain.support

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.models.MessageRecord
import com.circaso.messaging.domain.repositories.DeliveryRepository
import com.circaso.shared.UserId
import java.util.UUID

/**
 * Who sent a message in a chat, from its `message_records` record, written with every send and kept 30 days, so it is
 * known after every copy has been delivered and deleted (decision 2026-10-27). M14 (sender only) and read receipts
 * (to the sender) ask it. Null for an id never sent in [ChatId], or past 30 days.
 */
class MessageSenders(private val delivery: DeliveryRepository) {
    suspend fun recordIn(chatId: ChatId, messageId: UUID): MessageRecord? =
        delivery.messageRecord(messageId)?.takeIf { it.chatId == chatId }

    suspend fun senderOf(chatId: ChatId, messageId: UUID): UserId? = recordIn(chatId, messageId)?.senderId
}
