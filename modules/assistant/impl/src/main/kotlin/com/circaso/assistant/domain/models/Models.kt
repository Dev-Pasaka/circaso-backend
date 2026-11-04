package com.circaso.assistant.domain.models

import com.circaso.integrations.api.ModelPurpose
import com.circaso.shared.UserId
import java.time.Instant
import java.util.UUID

enum class Role { USER, ASHA }

/** What Asha read from the user's chats to answer one question: kept as a count and chat ids, never the snippets. */
data class ReadNote(val chatIds: List<String>, val messageCount: Int, val chatNames: List<String> = emptyList())

/** One line of the Asha chat, in one of the user's conversations. A reply carries the id of the message it answers. */
data class ChatMessage(
    val userId: UserId,
    val messageId: UUID,
    val role: Role,
    val text: String,
    val replyTo: UUID?,
    /** On a question: the id of Asha's answer, so deleting one deletes the other. */
    val answeredBy: UUID?,
    val askedFrom: String?,
    val readNote: ReadNote?,
    val createdAt: Instant,
    val conversationId: UUID,
)

/**
 * One conversation with Asha (decision 2026-10-31): the user can start a new one at any time, and Asha reads only the
 * one she is in. [title] is null until it is titled, from its first question or by the user.
 */
data class Conversation(
    val userId: UserId,
    val conversationId: UUID,
    val title: String?,
    val createdAt: Instant,
    /** The last change of any kind: a message, a rename. */
    val updatedAt: Instant,
    /** The last message, or the creation of a conversation that has none yet; the list is newest first by it. */
    val lastMessageAt: Instant,
)

/** Where the next page of conversations starts: after this one, in the list's order. */
data class ConversationCursor(val lastMessageAt: Instant, val conversationId: UUID)

data class ConversationPage(val conversations: List<Conversation>, val nextCursor: String?)

enum class Plan { FREE, PLUS }

data class Limits(
    val textTurnsPerDay: Int,
    val voiceSecondsPerDay: Int,
    /** How many earlier messages Asha is given as context. */
    val contextMessages: Int,
)

data class Usage(
    val plan: Plan,
    val textTurnsUsed: Int,
    val textTurnsLeft: Int,
    val voiceSecondsUsed: Int,
    val voiceSecondsLeft: Int,
    val resetsAt: Instant,
)

data class AskedFrom(val chatId: String, val chatName: String?)

data class TurnCommand(
    val userId: UserId,
    val text: String,
    val askedFrom: AskedFrom?,
    val chatReading: Boolean,
    /** The user's read-only agent token, which Asha's tools use against the other modules' `/v1/agent/` routes. */
    val agentToken: String?,
    val requestId: String?,
    /** A live voice turn runs on the faster voice model; tools, limits and audit are the same as a typed turn's. */
    val purpose: ModelPurpose = ModelPurpose.CHAT,
    /** The user's display currency (ISO 4217), for amounts in their own money; null when the app sent none. */
    val currency: String? = null,
    /** The conversation to ask in; null for the user's most recent one, or a new one if they have none. */
    val conversationId: UUID? = null,
)

/**
 * One tool call as the app is told about it (decision 2026-10-31): `tool_status` before it runs, `tool_done` after.
 * [label] is the server's own line for it, never the model's. [mascot] is what the mascot does meanwhile (decision
 * 2026-11-04): on `tool_status` the work the tool is, on `tool_done` thinking.
 */
data class ToolStatus(
    val turnId: UUID,
    val callId: String,
    val tool: String,
    val label: String,
    val mascot: MascotHints? = null,
)

/** What a turn comes to: an answer, or a question for the phone (a device tool) that the phone answers through A9. */
sealed interface TurnResult {
    /** The conversation the turn is in. */
    val conversationId: UUID

    /** [conversation] is as saved with the answer: null when it was deleted while Asha answered (nothing saved). */
    data class Answer(val message: ChatMessage, val conversation: Conversation? = null) : TurnResult {
        override val conversationId: UUID get() = message.conversationId
    }

    data class NeedsDevice(
        val turnId: UUID,
        val callId: String,
        val toolName: String,
        val argumentsJson: String,
        override val conversationId: UUID,
    ) : TurnResult
}

/** One decrypted message the phone sends back for a device tool. Used for the turn and dropped. */
data class ChatSnippet(
    val chatId: String,
    val chatName: String?,
    val sender: String,
    val at: Instant,
    val text: String,
)

data class DeviceToolResult(
    val userId: UserId,
    val turnId: UUID,
    val callId: String,
    val snippets: List<ChatSnippet>,
    val agentToken: String?,
    val requestId: String?,
)

data class ChatPage(val messages: List<ChatMessage>, val nextCursor: String?)

data class VoiceClip(
    val audio: ByteArray,
    val contentType: String,
    val claimedSeconds: Int,
    val language: String?,
)
