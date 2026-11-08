package com.circaso.support.data.cache

import com.circaso.infrastructure.api.RedisScope
import com.circaso.shared.UserId
import com.circaso.support.domain.ports.TicketAllowance
import java.time.Duration

/**
 * `sup:opened:{userId}`, a counter whose window starts at the user's first new ticket and lasts [window] (a day):
 * the gateway's fixed-window shape, so a burst of tickets is held to the cap without a sorted set per user. The key
 * always carries its TTL, so it never outlives the window.
 */
class RedisTicketAllowance(private val redis: RedisScope) : TicketAllowance {
    override suspend fun take(
        userId: UserId,
        max: Long,
        window: Duration,
    ): Long? {
        val key = "opened:$userId"
        val count = redis.increment(key, window)
        if (count <= max) return null
        return redis.ttl(key)?.let { (it.toMillis() + MILLIS_IN_SECOND - 1) / MILLIS_IN_SECOND }?.coerceAtLeast(1)
            ?: window.seconds
    }

    private companion object {
        const val MILLIS_IN_SECOND = 1_000
    }
}
