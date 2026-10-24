package com.circaso.assistant.domain.ports

import com.circaso.integrations.api.ToolSpec
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Duration
import java.util.UUID

/** Asha's read-only tools on the other modules, called with the user's agent token. */
interface AgentTools {
    /** The tools available to this turn, for the model. */
    fun specs(): List<ToolSpec>

    fun handles(toolName: String): Boolean

    /** The tool's answer as text for the model, or an error. Content is untrusted: it may come from other people. */
    suspend fun call(
        toolName: String,
        argumentsJson: String,
        agentToken: String,
    ): Resource<String>
}

/** The questions Asha asks the phone, which holds the keys to the encrypted chats. */
object DeviceTools {
    const val SEARCH_CHATS = "searchChats"
    const val READ_CHAT = "readChat"
    val names = setOf(SEARCH_CHATS, READ_CHAT)
}

/** A turn paused while the phone answers a device tool (A9). Kept for 10 minutes. */
data class PendingTurn(
    val userId: UserId,
    val turnId: UUID,
    val askedFrom: String?,
    /** The user's message that started the turn, saved with the answer when the turn ends. */
    val userText: String,
    val userMessageId: UUID,
    val callId: String,
    val toolName: String,
    /** The conversation so far, as the model has seen it, ready to continue. */
    val conversation: List<PendingMessage>,
    val readNote: PendingReadNote,
    val steps: Int,
)

sealed interface PendingMessage {
    data class User(val text: String) : PendingMessage

    data class Assistant(val text: String?, val calls: List<PendingCall>) : PendingMessage

    data class ToolResult(val callId: String, val name: String, val content: String) : PendingMessage
}

data class PendingCall(val id: String, val name: String, val argumentsJson: String)

data class PendingReadNote(val chatIds: List<String>, val chatNames: List<String>, val messageCount: Int)

interface PendingTurns {
    suspend fun save(turn: PendingTurn)

    /** Reads and removes it, so an answer can't be used twice. */
    suspend fun take(userId: UserId, turnId: UUID): PendingTurn?
}

/** Daily allowance counters (`ast:quota:…`). */
interface QuotaCounters {
    /** Counts one text turn; the count today after this one. */
    suspend fun countTextTurn(userId: UserId, day: String): Long

    suspend fun textTurns(userId: UserId, day: String): Long

    suspend fun addVoiceSeconds(
        userId: UserId,
        day: String,
        seconds: Int,
    ): Long

    suspend fun voiceSeconds(userId: UserId, day: String): Long

    suspend fun addTokens(
        userId: UserId,
        day: String,
        input: Int,
        output: Int,
    )

    suspend fun tokens(userId: UserId, day: String): Pair<Long, Long>
}

/** Conversation text is encrypted at rest: it is not end-to-end encrypted, because Asha has to read it. */
interface TextVault {
    fun seal(text: String): String

    fun open(sealed: String): String
}

@Suppress("unused")
private val keep = Duration.ZERO
