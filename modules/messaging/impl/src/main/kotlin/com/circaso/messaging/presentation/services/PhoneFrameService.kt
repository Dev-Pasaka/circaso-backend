package com.circaso.messaging.presentation.services

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.usecases.AckDelivered
import com.circaso.messaging.domain.usecases.MarkRead
import com.circaso.messaging.domain.usecases.RelayTyping
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.util.UUID

/** The frames the phone sends over the socket: `messaging.ack`, `messaging.read` and `messaging.typing`. */
class PhoneFrameService(
    private val ackDelivered: AckDelivered,
    private val markRead: MarkRead,
    private val relayTyping: RelayTyping,
) {
    suspend fun ack(userId: UserId, messageIds: List<UUID>): Resource<Int> = ackDelivered(userId, messageIds)

    suspend fun read(
        userId: UserId,
        chatId: ChatId,
        upTo: UUID,
    ): Resource<Unit> =
        markRead(
            userId,
            chatId,
            upTo,
        )

    suspend fun typing(
        userId: UserId,
        chatId: ChatId,
        on: Boolean,
    ): Resource<Unit> =
        relayTyping(
            userId,
            chatId,
            on,
        )
}
