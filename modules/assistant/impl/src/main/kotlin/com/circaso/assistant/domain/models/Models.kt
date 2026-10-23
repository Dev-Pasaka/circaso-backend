package com.circaso.assistant.domain.models

import com.circaso.shared.UserId
import java.time.Instant
import java.util.UUID

enum class Role { USER, ASHA }

/** What Asha read from the user's chats to answer one question: kept as a count and chat ids, never the snippets. */
data class ReadNote(val chatIds: List<String>, val messageCount: Int, val chatNames: List<String> = emptyList())

/** One line of the Asha chat. A reply carries the id of the message it answers. */
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
)

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
)

/** What a turn comes to: an answer, or a question for the phone (a device tool) that the phone answers through A9. */
sealed interface TurnResult {
    data class Answer(val message: ChatMessage) : TurnResult
    data class NeedsDevice(val turnId: UUID, val callId: String, val toolName: String, val argumentsJson: String) : TurnResult
}

/** One decrypted message the phone sends back for a device tool. Used for the turn and dropped. */
data class ChatSnippet(val chatId: String, val chatName: String?, val sender: String, val at: Instant, val text: String)

data class DeviceToolResult(
    val userId: UserId,
    val turnId: UUID,
    val callId: String,
    val snippets: List<ChatSnippet>,
    val agentToken: String?,
    val requestId: String?,
)

data class ChatPage(val messages: List<ChatMessage>, val nextCursor: String?)

data class VoiceClip(val audio: ByteArray, val contentType: String, val claimedSeconds: Int, val language: String?)
