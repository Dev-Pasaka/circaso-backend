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
 * `msg:members:{chatId}`: the member ids as one comma-separated value for 10 minutes (`RedisScope` has no set
 * commands; a full group is about 37 KB, read in one `GET`). A change writes [CHANGED] for a few seconds in its
 * place; readers fill with `SET NX`, so they can't fill over the marker with a list read before the change
 * (decision 2026-10-26).
 */
class RedisMembersCache(private val redis: RedisScope) : MembersCache {
    override suspend fun get(chatId: ChatId): Set<UserId>? {
        val raw = redis.get(key(chatId)) ?: return null
        if (raw == CHANGED) return null
        return raw
            .split(',')
            .mapNotNull { UserId.parse(it) }
            .toSet()
    }

    override suspend fun fillIfAbsent(chatId: ChatId, members: Set<UserId>) {
        redis.setIfAbsent(key(chatId), members.joinToString(","), TTL)
    }

    override suspend fun markChanged(chatId: ChatId) = redis.set(key(chatId), CHANGED, CHANGED_TTL)

    private fun key(chatId: ChatId) = "members:$chatId"

    companion object {
        /** Not a user id, so it can't be mistaken for a list. */
        const val CHANGED = "changed"
        val TTL: Duration = Duration.ofMinutes(10)

        /** Longer than a read of the rows takes, short enough that the cache is back in use soon. */
        val CHANGED_TTL: Duration = Duration.ofSeconds(5)
    }
}
