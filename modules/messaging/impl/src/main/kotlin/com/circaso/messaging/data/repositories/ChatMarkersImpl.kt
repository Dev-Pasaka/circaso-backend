package com.circaso.messaging.data.repositories

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.repositories.ChatMarkers
import com.circaso.shared.UserId
import java.util.UUID

private const val LAST_MESSAGE_CQL =
    "UPDATE circaso_messaging.chats_by_user SET last_message_id = ? WHERE user_id = ? AND chat_id = ?"

private const val READ_UP_TO_CQL =
    "UPDATE circaso_messaging.chats_by_user SET read_up_to = ? WHERE user_id = ? AND chat_id = ?"

/**
 * The markers are single-column updates, not Kandra's full-row save: a save would write every column of a line
 * another request may be changing. An update of a line deleted a moment before (the person left) leaves a line with
 * only the marker set; [ChatRepositoryImpl] skips such a line when it reads the chat list.
 */
class ChatMarkersImpl(private val batches: UnloggedBatches) : ChatMarkers {
    override suspend fun lastMessage(
        userIds: Collection<UserId>,
        chatId: ChatId,
        messageId: UUID,
        batchSize: Int,
    ) = batches.run(LAST_MESSAGE_CQL, userIds.map { listOf(messageId, it.value, chatId.value) }, batchSize)

    override suspend fun readUpTo(
        userId: UserId,
        chatId: ChatId,
        messageId: UUID,
    ) = batches.run(READ_UP_TO_CQL, listOf(listOf(messageId, userId.value, chatId.value)), batchSize = 1)
}
