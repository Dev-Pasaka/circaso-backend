package com.circaso.messaging.data.cache

import com.circaso.infrastructure.api.RedisScope
import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.ports.BundleRateLimit
import com.circaso.messaging.domain.ports.MembersCache
import com.circaso.shared.UserId
import java.time.Duration

/**
 * Messaging's Redis prefix: every key it writes is `msg:<purpose>:<id>` (`msg:members:{chatId}`,
 * `msg:pending:{messageId}`, `msg:seq:{userId}` …, Messaging → Data → Redis). The keys arrive with their slices.
 */
const val REDIS_PREFIX = "msg"

/** `msg:bundle-rate:{userId}`: a counter whose hour starts with the first fetch (Messaging → Data → Redis). */
class RedisBundleRateLimit(private val redis: RedisScope) : BundleRateLimit {
    override suspend fun count(userId: UserId): Long = redis.increment(key(userId), WINDOW)

    override suspend fun retryAfter(userId: UserId): Duration? = redis.ttl(key(userId))

    private fun key(userId: UserId) = "bundle-rate:$userId"

    private companion object {
        val WINDOW: Duration = Duration.ofHours(1)
    }
}

/**
 * `msg:members:{chatId}`: the member ids as one comma-separated value for 10 minutes. A plain value rather than a
 * Redis set because `RedisScope` has no set commands; a full group is about 37 KB, read in one `GET`.
 */
class RedisMembersCache(private val redis: RedisScope) : MembersCache {
    override suspend fun get(chatId: ChatId): Set<UserId>? {
        val raw = redis.get(key(chatId)) ?: return null
        return raw
            .split(',')
            .mapNotNull { UserId.parse(it) }
            .toSet()
    }

    override suspend fun put(chatId: ChatId, members: Set<UserId>) =
        redis.set(key(chatId), members.joinToString(","), TTL)

    override suspend fun clear(chatId: ChatId) {
        redis.delete(key(chatId))
    }

    private fun key(chatId: ChatId) = "members:$chatId"

    private companion object {
        val TTL: Duration = Duration.ofMinutes(10)
    }
}
