package com.circaso.assistant.domain.ports

import com.circaso.assistant.domain.models.AshaVoice
import com.circaso.assistant.domain.models.LiveEvent
import com.circaso.assistant.domain.models.MascotSceneState
import com.circaso.assistant.domain.models.TokenCounts
import com.circaso.integrations.api.SpokenAudio
import com.circaso.integrations.api.ToolSpec
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Duration
import java.util.UUID

/**
 * Asha's server-side tools: the other modules' read-only `/v1/agent/` routes, called with the user's agent token, and
 * the few that need no token (web search, her own allowance). All of them only read (decision 2026-10-31), but one:
 * `openSupportTicket` writes a support ticket the user agreed to (decision 2026-11-08).
 */
interface AgentTools {
    /** The tools available to this turn, for the model; the token-bound ones only when [hasAgentToken]. */
    fun specs(hasAgentToken: Boolean): List<ToolSpec>

    fun handles(toolName: String): Boolean

    /**
     * What the app shows while the tool runs ("Checking your payments for October…"): written by the server from the
     * tool and its arguments, never by the model.
     */
    fun label(toolName: String, argumentsJson: String): String

    /**
     * The tool's answer as JSON for the model, at most a few thousand characters, or an error. Content is untrusted:
     * it may come from other people or the web.
     */
    suspend fun call(
        toolName: String,
        argumentsJson: String,
        context: ToolContext,
    ): Resource<String>
}

/** Who a tool acts for: the user, their agent token (null when the app sent none), and their display currency. */
data class ToolContext(
    val userId: UserId,
    val agentToken: String?,
    val currency: String?,
    /** The tools already called this turn, in order (decision 2026-11-05: a tool may cap its calls per turn). */
    val calledBefore: List<String> = emptyList(),
) {
    override fun toString(): String = "ToolContext($userId, currency=$currency)"
}

/** The questions Asha asks the phone, which holds the keys to the encrypted chats. */
object DeviceTools {
    const val SEARCH_CHATS = "searchChats"
    const val READ_CHAT = "readChat"
    val names = setOf(SEARCH_CHATS, READ_CHAT)

    /** What the app shows while the phone looks (the phone may show its own instead). */
    fun label(toolName: String): String =
        if (toolName == READ_CHAT) "Reading the chat on your phone…" else "Searching your chats on your phone…"
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
    /** The user's display currency for this turn, so the tools after the pause answer in it too. */
    val currency: String? = null,
    /** The conversation the turn is in; null only for a turn parked before conversations (it goes to the latest). */
    val conversationId: UUID? = null,
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

    /** The call the turn waits on, without taking it: a wrong `callId` must leave the turn waiting. */
    suspend fun callIdOf(userId: UserId, turnId: UUID): String?

    /** Reads and removes it, so an answer can't be used twice. */
    suspend fun take(userId: UserId, turnId: UUID): PendingTurn?

    /** Removes every turn the user has parked, with the conversation each one holds (a deleted account). */
    suspend fun forget(userId: UserId)
}

/** Daily allowance counters (`ast:quota:…`). */
interface QuotaCounters {
    /**
     * Counts one text turn unless the day already holds [cap]; the count with this one. Above [cap] means refused,
     * and a refused turn is given back, so the day's count never ends above [cap] however many arrive at once.
     */
    suspend fun countTextTurn(
        userId: UserId,
        day: String,
        cap: Int,
    ): Long

    suspend fun textTurns(userId: UserId, day: String): Long

    suspend fun addVoiceSeconds(
        userId: UserId,
        day: String,
        seconds: Int,
    ): Long

    suspend fun voiceSeconds(userId: UserId, day: String): Long

    /**
     * Adds up to [seconds], never taking the day's count past [cap], even with two sockets metering at once; the count
     * after. Seconds over the cap are not charged: they end the conversation instead.
     */
    suspend fun addVoiceSecondsUpTo(
        userId: UserId,
        day: String,
        seconds: Int,
        cap: Int,
    ): Long

    suspend fun addTokens(
        userId: UserId,
        day: String,
        input: Int,
        output: Int,
    )

    suspend fun tokens(userId: UserId, day: String): Pair<Long, Long>

    /**
     * Adds a turn's tokens by model, split as the provider prices them (decision 2026-11-08), next to the totals of
     * [addTokens]; the day's figures per model for the cost report.
     */
    suspend fun addModelTokens(
        userId: UserId,
        day: String,
        byModel: Map<String, TokenCounts>,
    )

    /** The day's tokens by model; empty when there are none. */
    suspend fun modelTokens(userId: UserId, day: String): Map<String, TokenCounts>

    /**
     * Counts one web search unless the day already holds [cap] (decision 2026-11-05), as [countTextTurn] counts a
     * turn: the count with this one, and above [cap] means refused and given back.
     */
    suspend fun countWebSearch(
        userId: UserId,
        day: String,
        cap: Int,
    ): Long

    suspend fun webSearches(userId: UserId, day: String): Long

    /** Removes the user's counters for [days] (a deleted account). */
    suspend fun forget(userId: UserId, days: List<String>)
}

/** Conversation text is encrypted at rest: it is not end-to-end encrypted, because Asha has to read it. */
interface TextVault {
    fun seal(text: String): String

    fun open(sealed: String): String
}

@Suppress("unused")
private val keep = Duration.ZERO

/**
 * Where each live voice turn's timing goes (decision 2026-10-30): one log line of its durations and the first-audio
 * latency histogram. Durations only, never what was said.
 */
fun interface LiveTimings {
    fun record(timing: LiveEvent.Timing)
}

/**
 * Told when a request asked for no voice, or one not on Asha's list, and [used] the default instead (decision
 * 2026-10-31). Not an error: a debug line, so an old app or a stale choice can be spotted without noise.
 */
fun interface VoiceFallbacks {
    fun fellBack(requested: String?, used: AshaVoice)
}

/**
 * Each voice's preview clip, kept a while so trying voices doesn't call the voice provider each time. A miss or a
 * failure to keep one is never an error: the clip is made again.
 */
interface VoiceSamples {
    suspend fun find(voiceId: String, text: String): SpokenAudio?

    suspend fun save(
        voiceId: String,
        text: String,
        audio: SpokenAudio,
    )
}

/**
 * Each conversation's mascot scene between turns (decision 2026-11-04), for the hysteresis in TurnMascot. A lookup
 * that fails, or a state lost, is never an error: the mascot starts again from the default scene.
 */
interface MascotScenes {
    suspend fun find(userId: UserId, conversationId: UUID): MascotSceneState?

    suspend fun save(
        userId: UserId,
        conversationId: UUID,
        state: MascotSceneState,
    )

    /** Every conversation's scene of [userId] (their account is gone). */
    suspend fun forget(userId: UserId)
}
