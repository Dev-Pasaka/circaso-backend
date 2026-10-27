package com.circaso.messaging.presentation.services

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.models.InboxPage
import com.circaso.messaging.domain.models.MessagingSettings
import com.circaso.messaging.domain.models.SendMessageCommand
import com.circaso.messaging.domain.models.SentMessage
import com.circaso.messaging.domain.usecases.AckDelivered
import com.circaso.messaging.domain.usecases.DeleteForEveryone
import com.circaso.messaging.domain.usecases.ReadInbox
import com.circaso.messaging.domain.usecases.SendMessage
import com.circaso.messaging.domain.usecases.UpdateSettings
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.util.UUID

/** Sending and delivery over HTTP: M12 to M14, and the M22 settings. */
class MessageService(
    private val sendMessage: SendMessage,
    private val readInbox: ReadInbox,
    private val ackDelivered: AckDelivered,
    private val deleteForEveryone: DeleteForEveryone,
    private val updateSettings: UpdateSettings,
) {
    suspend fun send(command: SendMessageCommand): Resource<SentMessage> = sendMessage(command)

    suspend fun inbox(
        userId: UserId,
        after: UUID?,
        limit: Int,
    ): Resource<InboxPage> =
        readInbox(
            userId,
            after,
            limit,
        )

    suspend fun ack(userId: UserId, messageIds: List<UUID>): Resource<Int> = ackDelivered(userId, messageIds)

    suspend fun delete(
        caller: UserId,
        chatId: ChatId,
        messageId: UUID,
    ): Resource<Unit> =
        deleteForEveryone(
            caller,
            chatId,
            messageId,
        )

    suspend fun settings(
        userId: UserId,
        readReceipts: Boolean?,
        typing: Boolean?,
    ): Resource<MessagingSettings> =
        updateSettings(
            userId,
            readReceipts,
            typing,
        )
}
