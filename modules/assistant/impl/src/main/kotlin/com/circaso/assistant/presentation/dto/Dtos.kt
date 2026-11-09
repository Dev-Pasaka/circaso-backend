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
    /** The conversation to ask in (C1, C2); without it, the user's most recent one, or a new one if they have none. */
    val conversationId: String? = null,
    /** The app screen the user asked from (Asha's bubble, 2026-11-09), for example `Wallet › Send`; at most 80 chars. */
    val screen: String? = null,
    /** A picture of the user's app screen for this question only; read by the model, never stored. */
    val image: ImageDto? = null,
)

/** A picture: [data] is its bytes in base64, [mediaType] `image/jpeg`, `image/png` or `image/webp`. */
@Serializable
data class ImageDto(val mediaType: String, val data: String)

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
    val conversationId: String,
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

/**
 * A7. The flat fields are the original answer; the period and the meters (added 2026-11-05, for the app's usage menu)
 * say the same with each limit, which is null when there is none (`ASSISTANT_LIMITS_UNLIMITED`). The period is the
 * UTC day: `periodEnd` and `resetsAt` are the same instant.
 */
@Serializable
data class UsageResponse(
    val plan: String,
    val textTurnsUsed: Int,
    val textTurnsLeft: Int,
    val voiceSecondsUsed: Int,
    val voiceSecondsLeft: Int,
    @Serializable(with = InstantSerializer::class) val resetsAt: Instant,
    @Serializable(with = InstantSerializer::class) val periodStart: Instant,
    @Serializable(with = InstantSerializer::class) val periodEnd: Instant,
    val messages: UsageMeterDto,
    val voiceSeconds: UsageMeterDto,
    val searches: UsageMeterDto,
)

/** One allowance today: how much was [used] and the day's [limit] (null: no limit). */
@Serializable
data class UsageMeterDto(val used: Int, val limit: Int? = null)

@Serializable
data class TranscriptResponse(val text: String, val seconds: Int)

@Serializable
data class SpeechRequest(
    val text: String,
    val language: String? = null,
    /** A voice id from A12; missing or unknown, Asha's default voice speaks. */
    val voice: String? = null,
)

/** One voice in A12's list; [gender] is `female` or `male`, a hint for the picker. */
@Serializable
data class VoiceDto(
    val id: String,
    val name: String,
    val character: String,
    val gender: String,
    val default: Boolean,
)

@Serializable
data class VoicesResponse(val voices: List<VoiceDto>)

/** What the SSE events carry (`message`, `tool_request`, `done`, `error`). */
@Serializable
data class DeltaEvent(val delta: String)

@Serializable
data class ToolRequestEvent(
    val turnId: String,
    val callId: String,
    val name: String,
    val arguments: String,
    val conversationId: String? = null,
)

/**
 * The saved reply, and the conversation it was saved in (C1's shape, with its title after the first exchange);
 * [conversation] is null when the conversation was deleted while Asha answered, and nothing was saved.
 */
@Serializable
data class DoneEvent(
    val message: ChatMessageDto,
    val conversationId: String,
    val conversation: ConversationDto? = null,
)

/** One conversation with Asha (C1 to C3). [title] is "New chat" until its first question titles it. */
@Serializable
data class ConversationDto(
    val conversationId: String,
    val title: String,
    @Serializable(with = InstantSerializer::class) val createdAt: Instant,
    @Serializable(with = InstantSerializer::class) val updatedAt: Instant,
    @Serializable(with = InstantSerializer::class) val lastMessageAt: Instant,
)

@Serializable
data class ConversationsResponse(val conversations: List<ConversationDto>, val nextCursor: String? = null)

/** C2: a new, empty conversation; [title] is optional (without one, the first question titles it). */
@Serializable
data class CreateConversationRequest(val title: String? = null)

/** C3: the user's own title, 1 to 80 characters. */
@Serializable
data class RenameConversationRequest(val title: String)

/** Asha is about to use a tool: [label] is the server's line for it ("Checking your payments…"). */
@Serializable
data class ToolStatusEvent(
    val turnId: String,
    val callId: String,
    val tool: String,
    val label: String,
    val scene: String? = null,
    val mood: String? = null,
    val activity: String? = null,
    val reaction: String? = null,
    val sensitive: Boolean? = null,
)

/** That tool has answered; [ok] false when the lookup failed. */
@Serializable
data class ToolDoneEvent(
    val turnId: String,
    val callId: String,
    val tool: String,
    val ok: Boolean,
    val scene: String? = null,
    val mood: String? = null,
    val activity: String? = null,
    val reaction: String? = null,
    val sensitive: Boolean? = null,
)

/**
 * The mascot's hints (decision 2026-11-04): SSE `mascot`, once the turn's hints are known (before its first text),
 * when they change (a chart being drawn), and at the end (activity `done`). Values from the fixed lists only.
 */
@Serializable
data class MascotEvent(
    val scene: String,
    val mood: String,
    val activity: String,
    val reaction: String? = null,
    val sensitive: Boolean = false,
)

/** The turn failed after its reply had started streaming, so the status line was already sent: nothing is saved. */
@Serializable
data class ErrorEvent(val code: String, val message: String)
