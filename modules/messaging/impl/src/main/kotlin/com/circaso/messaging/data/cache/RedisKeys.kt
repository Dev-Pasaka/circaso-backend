package com.circaso.messaging.data.cache

import com.circaso.infrastructure.api.RedisScope
import com.circaso.messaging.domain.ports.BundleRateLimit
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
