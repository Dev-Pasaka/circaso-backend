package com.circaso.assistant.presentation.dto

import com.circaso.shared.InstantSerializer
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class AskedFromDto(val chatId: String, val chatName: String? = null)

/** A typed, spoken (already transcribed) or asked-from-another-chat message to Asha. */
@Serializable
data class SendMessageRequest(
    val text: String,
    val askedFrom: AskedFromDto? = null,
    /** Whether the user lets Asha read chats on the phone. Off removes the chat tools from the turn. */
    val chatReading: Boolean = true,
    /** The user's display currency (ISO 4217, `KES`): Asha shows amounts in it, at today's rate. */
    val currency: String? = null,
)

@Serializable
data class ReadNoteDto(val chatIds: List<String>, val chatNames: List<String> = emptyList(), val messageCount: Int)

@Serializable
data class ChatMessageDto(
    val messageId: String,
    val role: String,
    val text: String,
    val replyTo: String? = null,
    val askedFrom: String? = null,
    val readNote: ReadNoteDto? = null,
    @Serializable(with = InstantSerializer::class) val at: Instant,
)

@Serializable
data class ChatResponse(val messages: List<ChatMessageDto>, val nextCursor: String? = null)

@Serializable
data class SnippetDto(
    val chatId: String,
    val chatName: String? = null,
    val sender: String,
    @Serializable(with = InstantSerializer::class) val at: Instant,
    val text: String,
)

/** The phone's answer to a device tool: only the matching snippets, never whole chats. */
@Serializable
data class ToolResultsRequest(val callId: String, val snippets: List<SnippetDto> = emptyList())

@Serializable
data class UsageResponse(
    val plan: String,
    val textTurnsUsed: Int,
    val textTurnsLeft: Int,
    val voiceSecondsUsed: Int,
    val voiceSecondsLeft: Int,
    @Serializable(with = InstantSerializer::class) val resetsAt: Instant,
)

@Serializable
data class TranscriptResponse(val text: String, val seconds: Int)

@Serializable
data class SpeechRequest(val text: String, val language: String? = null)

/** What the SSE events carry (`message`, `tool_request`, `done`, `error`). */
@Serializable
data class DeltaEvent(val delta: String)

@Serializable
data class ToolRequestEvent(
    val turnId: String,
    val callId: String,
    val name: String,
    val arguments: String,
)

@Serializable
data class DoneEvent(val message: ChatMessageDto)

/** Asha is about to use a tool: [label] is the server's line for it ("Checking your payments…"). */
@Serializable
data class ToolStatusEvent(
    val turnId: String,
    val callId: String,
    val tool: String,
    val label: String,
)

/** That tool has answered; [ok] false when the lookup failed. */
@Serializable
data class ToolDoneEvent(
    val turnId: String,
    val callId: String,
    val tool: String,
    val ok: Boolean,
)

/** The turn failed after its reply had started streaming, so the status line was already sent: nothing is saved. */
@Serializable
data class ErrorEvent(val code: String, val message: String)
