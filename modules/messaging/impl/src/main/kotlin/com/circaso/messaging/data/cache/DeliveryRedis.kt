package com.circaso.messaging.data.cache

import com.circaso.infrastructure.api.RedisScope
import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.ReceiptStatus
import com.circaso.messaging.domain.ports.PendingCounts
import com.circaso.messaging.domain.ports.ReceiptBuffer
import com.circaso.messaging.domain.ports.TypingMarks
import com.circaso.shared.UserId
import java.time.Duration
import java.util.UUID

/**
 * `msg:pending:{messageId}`: the members yet to ack a group message, for 30 days like the body. A count-down checks
 * the counter is there first, so a counter Redis lost is never re-created at -1, which would delete the body under
 * everyone still waiting for it.
 */
class RedisPendingCounts(private val redis: RedisScope) : PendingCounts {
    override suspend fun start(messageId: UUID, count: Int) = redis.set(key(messageId), count.toString(), TTL)

    override suspend fun countDown(messageId: UUID): Long? {
        if (!redis.exists(key(messageId))) return null
        return redis.incrementBy(key(messageId), -1, TTL)
    }

    override suspend fun clear(messageId: UUID) {
        redis.delete(key(messageId))
    }

    private fun key(messageId: UUID) = "pending:$messageId"

    private companion object {
        val TTL: Duration = Duration.ofDays(30)
    }
}

/**
 * `msg:receipts:{messageId}`: a hash with one field per person and status (`<userId>:delivered`, `<userId>:read`),
 * kept 10 seconds after its last receipt, so a delivered receipt written after a read one can't replace it; a take
 * keeps each person's furthest status. `msg:receipts-due:{messageId}` claims the next batch for one node for as long
 * as the batch window, so each window is sent once.
 */
class RedisReceiptBuffer(private val redis: RedisScope, private val window: Duration) : ReceiptBuffer {
    override suspend fun add(
        messageId: UUID,
        userId: UserId,
        status: ReceiptStatus,
    ) = redis.hashSet(key(messageId), mapOf("$userId:${status.name.lowercase()}" to "1"), TTL)

    override suspend fun claimBatch(messageId: UUID): Boolean = redis.setIfAbsent(dueKey(messageId), "1", window)

    override suspend fun takeBatch(messageId: UUID): Map<UserId, ReceiptStatus> {
        redis.delete(dueKey(messageId))
        return redis
            .hashTake(key(messageId))
            .keys
            .mapNotNull { parse(it) }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, statuses) -> statuses.maxBy { it.ordinal } }
    }

    private fun parse(field: String): Pair<UserId, ReceiptStatus>? {
        val id = UserId.parse(field.substringBefore(':')) ?: return null
        val status = field.substringAfter(':', "")
        val known = ReceiptStatus.entries.firstOrNull { it.name.equals(status, ignoreCase = true) } ?: return null
        return id to known
    }

    private fun key(messageId: UUID) = "receipts:$messageId"

    private fun dueKey(messageId: UUID) = "receipts-due:$messageId"

    private companion object {
        val TTL: Duration = Duration.ofSeconds(10)
    }
}

/** `msg:typing:{chatId}:{userId}` = `1` for 6 seconds. */
class RedisTypingMarks(private val redis: RedisScope, private val ttl: Duration) : TypingMarks {
    override suspend fun start(chatId: ChatId, userId: UserId): Boolean =
        redis.setIfAbsent(key(chatId, userId), "1", ttl)

    override suspend fun stop(chatId: ChatId, userId: UserId) {
        redis.delete(key(chatId, userId))
    }

    private fun key(chatId: ChatId, userId: UserId) = "typing:$chatId:$userId"
}
