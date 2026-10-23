package com.circaso.assistant.data.cache

import com.circaso.assistant.domain.ports.PendingCall
import com.circaso.assistant.domain.ports.PendingMessage
import com.circaso.assistant.domain.ports.PendingReadNote
import com.circaso.assistant.domain.ports.PendingTurn
import com.circaso.assistant.domain.ports.PendingTurns
import com.circaso.assistant.domain.ports.QuotaCounters
import com.circaso.infrastructure.api.RedisScope
import com.circaso.shared.CircasoJson
import com.circaso.shared.UserId
import kotlinx.serialization.Serializable
import java.time.Duration
import java.util.UUID

/** Daily allowance counters, `ast:quota:…`; each lives two days so a day's totals can still be read after midnight. */
class RedisQuotaCounters(private val redis: RedisScope) : QuotaCounters {
    private fun key(kind: String, userId: UserId, day: String) = "quota:$kind:$userId:$day"

    override suspend fun countTextTurn(userId: UserId, day: String): Long = redis.increment(key("text", userId, day), TTL)

    override suspend fun textTurns(userId: UserId, day: String): Long = read(key("text", userId, day))

    override suspend fun addVoiceSeconds(userId: UserId, day: String, seconds: Int): Long =
        redis.incrementBy(key("voice", userId, day), seconds.coerceAtLeast(0).toLong(), TTL)

    override suspend fun voiceSeconds(userId: UserId, day: String): Long = read(key("voice", userId, day))

    override suspend fun addTokens(userId: UserId, day: String, input: Int, output: Int) {
        redis.incrementBy(key("in", userId, day), input.coerceAtLeast(0).toLong(), TTL)
        redis.incrementBy(key("out", userId, day), output.coerceAtLeast(0).toLong(), TTL)
    }

    override suspend fun tokens(userId: UserId, day: String): Pair<Long, Long> = read(key("in", userId, day)) to read(key("out", userId, day))

    private suspend fun read(key: String): Long = redis.get(key)?.toLongOrNull() ?: 0

    private companion object {
        val TTL: Duration = Duration.ofDays(2)
    }
}

@Serializable
private data class PendingJson(
    val turnId: String,
    val askedFrom: String?,
    val userText: String,
    val callId: String,
    val toolName: String,
    val conversation: List<MessageJson>,
    val chatIds: List<String>,
    val chatNames: List<String>,
    val messageCount: Int,
    val steps: Int,
)

@Serializable
private data class MessageJson(
    val kind: String,
    val text: String? = null,
    val calls: List<CallJson> = emptyList(),
    val callId: String? = null,
    val name: String? = null,
    val content: String? = null,
)

@Serializable
private data class CallJson(val id: String, val name: String, val arguments: String)

/** A turn waiting for the phone, `ast:turn:{userId}:{turnId}`, kept 10 minutes and taken once. */
class RedisPendingTurns(private val redis: RedisScope) : PendingTurns {
    override suspend fun save(turn: PendingTurn) {
        val json = PendingJson(
            turn.turnId.toString(), turn.askedFrom, turn.userText, turn.callId, turn.toolName,
            turn.conversation.map {
                when (it) {
                    is PendingMessage.User -> MessageJson("user", text = it.text)
                    is PendingMessage.Assistant -> MessageJson("assistant", it.text, it.calls.map { c -> CallJson(c.id, c.name, c.argumentsJson) })
                    is PendingMessage.ToolResult -> MessageJson("tool", callId = it.callId, name = it.name, content = it.content)
                }
            },
            turn.readNote.chatIds, turn.readNote.chatNames, turn.readNote.messageCount, turn.steps,
        )
        redis.set("turn:${turn.userId}:${turn.turnId}", CircasoJson.encodeToString(PendingJson.serializer(), json), TTL)
    }

    override suspend fun take(userId: UserId, turnId: UUID): PendingTurn? {
        val raw = redis.getAndDelete("turn:$userId:$turnId") ?: return null
        val j = runCatching { CircasoJson.decodeFromString(PendingJson.serializer(), raw) }.getOrNull() ?: return null
        return PendingTurn(
            userId, turnId, j.askedFrom, j.userText, turnId, j.callId, j.toolName,
            j.conversation.map {
                when (it.kind) {
                    "user" -> PendingMessage.User(it.text.orEmpty())
                    "assistant" -> PendingMessage.Assistant(it.text, it.calls.map { c -> PendingCall(c.id, c.name, c.arguments) })
                    else -> PendingMessage.ToolResult(it.callId.orEmpty(), it.name.orEmpty(), it.content.orEmpty())
                }
            },
            PendingReadNote(j.chatIds, j.chatNames, j.messageCount), j.steps,
        )
    }

    private companion object {
        val TTL: Duration = Duration.ofMinutes(10)
    }
}
