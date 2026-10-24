package com.circaso.assistant.presentation.mappers

import com.circaso.assistant.domain.models.AskedFrom
import com.circaso.assistant.domain.models.ChatMessage
import com.circaso.assistant.domain.models.ChatPage
import com.circaso.assistant.domain.models.ChatSnippet
import com.circaso.assistant.domain.models.DeviceToolResult
import com.circaso.assistant.domain.models.TurnCommand
import com.circaso.assistant.domain.models.Usage
import com.circaso.assistant.presentation.dto.ChatMessageDto
import com.circaso.assistant.presentation.dto.ChatResponse
import com.circaso.assistant.presentation.dto.ReadNoteDto
import com.circaso.assistant.presentation.dto.SendMessageRequest
import com.circaso.assistant.presentation.dto.ToolResultsRequest
import com.circaso.assistant.presentation.dto.UsageResponse
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import java.util.UUID

fun SendMessageRequest.toCommand(
    userId: UserId,
    agentToken: String?,
    requestId: String?,
): Resource<TurnCommand> {
    if (text.isBlank()) return Resource.error(SharedError.Malformed(mapOf("text" to "is required")))
    return Resource.success(
        TurnCommand(
            userId,
            text,
            askedFrom?.let {
                AskedFrom(it.chatId, it.chatName)
            },
            chatReading,
            agentToken,
            requestId,
        ),
    )
}

fun ToolResultsRequest.toCommand(
    userId: UserId,
    turnId: String?,
    agentToken: String?,
    requestId: String?,
): Resource<DeviceToolResult> {
    val turn =
        runCatching { UUID.fromString(turnId) }.getOrNull()
            ?: return Resource.error(SharedError.Malformed(mapOf("messageId" to "is not valid")))
    if (callId.isBlank()) return Resource.error(SharedError.Malformed(mapOf("callId" to "is required")))
    return Resource.success(
        DeviceToolResult(
            userId,
            turn,
            callId,
            snippets.map {
                ChatSnippet(it.chatId, it.chatName, it.sender, it.at, it.text)
            },
            agentToken,
            requestId,
        ),
    )
}

fun ChatMessage.toDto() =
    ChatMessageDto(
        messageId.toString(),
        role.name,
        text,
        replyTo?.toString(),
        askedFrom,
        readNote?.let { ReadNoteDto(it.chatIds, it.chatNames, it.messageCount) },
        createdAt,
    )

fun ChatPage.toResponse() = ChatResponse(messages.map { it.toDto() }, nextCursor)

fun Usage.toResponse() =
    UsageResponse(
        plan.name,
        textTurnsUsed,
        textTurnsLeft,
        voiceSecondsUsed,
        voiceSecondsLeft,
        resetsAt,
    )

fun parseUuid(raw: String?): UUID? = raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }
