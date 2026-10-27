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
    private fun key(
        kind: String,
        userId: UserId,
        day: String,
    ) = "quota:$kind:$userId:$day"

    /**
     * `INCR` first, then give back the turn if it went over the cap, as [addVoiceSecondsUpTo] does: every refused caller
     * takes back exactly the one it added, so the count settles at the cap, and `usage_daily` never counts a refusal.
     */
    override suspend fun countTextTurn(
        userId: UserId,
        day: String,
        cap: Int,
    ): Long {
        val key = key("text", userId, day)
        val after = redis.increment(key, TTL)
        if (after > cap) redis.incrementBy(key, -1, TTL)
        return after
    }

    override suspend fun textTurns(userId: UserId, day: String): Long = read(key("text", userId, day))

    override suspend fun addVoiceSeconds(
        userId: UserId,
        day: String,
        seconds: Int,
    ): Long = redis.incrementBy(key("voice", userId, day), seconds.coerceAtLeast(0).toLong(), TTL)

    override suspend fun voiceSeconds(userId: UserId, day: String): Long = read(key("voice", userId, day))

    /**
     * `INCRBY` first, then give back what went over the cap. Each caller gives back at most its own seconds, and only
     * the part over the cap it saw, so concurrent callers together end exactly at the cap: never above it once they
     * have all returned, and never below what was charged before them.
     */
    override suspend fun addVoiceSecondsUpTo(
        userId: UserId,
        day: String,
        seconds: Int,
        cap: Int,
    ): Long {
        val key = key("voice", userId, day)
        val wanted = seconds.coerceAtLeast(0).toLong()
        val after = redis.incrementBy(key, wanted, TTL)
        val over = (after - cap).coerceIn(0, wanted)
        if (over == 0L) return after
        return redis.incrementBy(key, -over, TTL).coerceAtMost(cap.toLong())
    }

    override suspend fun addTokens(
        userId: UserId,
        day: String,
        input: Int,
        output: Int,
    ) {
        redis.incrementBy(key("in", userId, day), input.coerceAtLeast(0).toLong(), TTL)
        redis.incrementBy(key("out", userId, day), output.coerceAtLeast(0).toLong(), TTL)
    }

    override suspend fun tokens(userId: UserId, day: String): Pair<Long, Long> =
        read(key("in", userId, day)) to read(key("out", userId, day))

    private suspend fun read(key: String): Long = redis.get(key)?.toLongOrNull() ?: 0

    override suspend fun forget(userId: UserId, days: List<String>) {
        for (day in days) {
            for (kind in KINDS) redis.delete(key(kind, userId, day))
        }
    }

    private companion object {
        val TTL: Duration = Duration.ofDays(2)
        val KINDS = listOf("text", "voice", "in", "out")
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

/**
 * A turn waiting for the phone, `ast:turn:{userId}:{turnId}`, kept 10 minutes and taken once; `ast:turns:{userId}`
 * lists the user's parked turn ids for as long, and loses an id when its turn is taken.
 */
class RedisPendingTurns(private val redis: RedisScope) : PendingTurns {
    override suspend fun save(turn: PendingTurn) {
        val json =
            PendingJson(
                turn.turnId.toString(),
                turn.askedFrom,
                turn.userText,
                turn.callId,
                turn.toolName,
                turn.conversation.map {
                    when (it) {
                        is PendingMessage.User -> {
                            MessageJson("user", text = it.text)
                        }

                        is PendingMessage.Assistant -> {
                            MessageJson(
                                "assistant",
                                it.text,
                                it.calls.map { c ->
                                    CallJson(c.id, c.name, c.argumentsJson)
                                },
                            )
                        }

                        is PendingMessage.ToolResult -> {
                            MessageJson(
                                "tool",
                                callId = it.callId,
                                name = it.name,
                                content = it.content,
                            )
                        }
                    }
                },
                turn.readNote.chatIds,
                turn.readNote.chatNames,
                turn.readNote.messageCount,
                turn.steps,
            )
        redis.set("turn:${turn.userId}:${turn.turnId}", CircasoJson.encodeToString(PendingJson.serializer(), json), TTL)
        // The user's parked turn ids, so a deleted account's turns can be found without scanning keys.
        redis.hashSet("turns:${turn.userId}", mapOf(turn.turnId.toString() to "1"), TTL)
    }

    override suspend fun forget(userId: UserId) {
        for (turnId in redis.hashGetAll("turns:$userId").keys) redis.delete("turn:$userId:$turnId")
        redis.delete("turns:$userId")
    }

    override suspend fun callIdOf(userId: UserId, turnId: UUID): String? {
        val raw = redis.get("turn:$userId:$turnId") ?: return null
        return runCatching { CircasoJson.decodeFromString(PendingJson.serializer(), raw) }.getOrNull()?.callId
    }

    override suspend fun take(userId: UserId, turnId: UUID): PendingTurn? {
        // Out of the index first: a turn parked again under this id between the two steps puts its id back, so the
        // index may keep a stale id (harmless) but never misses a parked turn.
        redis.hashDelete("turns:$userId", turnId.toString())
        val raw = redis.getAndDelete("turn:$userId:$turnId") ?: return null
        val j = runCatching { CircasoJson.decodeFromString(PendingJson.serializer(), raw) }.getOrNull() ?: return null
        return PendingTurn(
            userId,
            turnId,
            j.askedFrom,
            j.userText,
            turnId,
            j.callId,
            j.toolName,
            j.conversation.map {
                when (it.kind) {
                    "user" -> {
                        PendingMessage.User(it.text.orEmpty())
                    }

                    "assistant" -> {
                        PendingMessage.Assistant(
                            it.text,
                            it.calls.map { c ->
                                PendingCall(c.id, c.name, c.arguments)
                            },
                        )
                    }

                    else -> {
                        PendingMessage.ToolResult(it.callId.orEmpty(), it.name.orEmpty(), it.content.orEmpty())
                    }
                }
            },
            PendingReadNote(j.chatIds, j.chatNames, j.messageCount),
            j.steps,
        )
    }

    private companion object {
        val TTL: Duration = Duration.ofMinutes(10)
    }
}
