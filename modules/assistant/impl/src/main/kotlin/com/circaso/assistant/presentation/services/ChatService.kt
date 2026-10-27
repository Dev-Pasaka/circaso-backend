package com.circaso.assistant.presentation.services

import com.circaso.assistant.domain.models.ChatPage
import com.circaso.assistant.domain.models.DeviceToolResult
import com.circaso.assistant.domain.models.TurnCommand
import com.circaso.assistant.domain.models.TurnResult
import com.circaso.assistant.domain.support.ReplyListener
import com.circaso.assistant.domain.usecases.ContinueTurn
import com.circaso.assistant.domain.usecases.DeleteChat
import com.circaso.assistant.domain.usecases.DeleteExchange
import com.circaso.assistant.domain.usecases.ListChat
import com.circaso.assistant.domain.usecases.StartTurn
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.util.UUID

/** The Asha chat: asking, the phone's answers to device tools, reading and deleting the history. */
class ChatService(
    private val startTurn: StartTurn,
    private val continueTurn: ContinueTurn,
    private val listChat: ListChat,
    private val deleteChat: DeleteChat,
    private val deleteExchange: DeleteExchange,
) {
    suspend fun ask(command: TurnCommand, listener: ReplyListener): Resource<TurnResult> = startTurn(command, listener)

    suspend fun answerDevice(result: DeviceToolResult, listener: ReplyListener): Resource<TurnResult> =
        continueTurn(result, listener)

    suspend fun listChat(
        userId: UserId,
        cursor: String?,
        limit: Int,
    ): Resource<ChatPage> =
        listChat.invoke(
            userId,
            cursor,
            limit,
        )

    suspend fun deleteAll(userId: UserId, requestId: String?): Resource<Unit> = deleteChat(userId, requestId)

    suspend fun deleteOne(
        userId: UserId,
        messageId: UUID,
        requestId: String?,
    ): Resource<Unit> =
        deleteExchange(
            userId,
            messageId,
            requestId,
        )
}
