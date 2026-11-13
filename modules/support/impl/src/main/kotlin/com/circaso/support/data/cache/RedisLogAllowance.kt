package com.circaso.support.data.cache

import com.circaso.infrastructure.api.RedisScope
import com.circaso.shared.UserId
import com.circaso.support.domain.ports.LogAllowance
import java.time.Duration

/** `sup:logs:{userId}`, a fixed-window counter like [RedisTicketAllowance], always with its TTL. */
class RedisLogAllowance(private val redis: RedisScope) : LogAllowance {
    override suspend fun take(
        userId: UserId,
        max: Long,
        window: Duration,
    ): Long? {
        val key = "logs:$userId"
        val count = redis.increment(key, window)
        if (count <= max) return null
        return redis.ttl(key)?.let { (it.toMillis() + MILLIS_IN_SECOND - 1) / MILLIS_IN_SECOND }?.coerceAtLeast(1)
            ?: window.seconds
    }

    private companion object {
        const val MILLIS_IN_SECOND = 1_000
    }
}
