package com.circaso.calls.data.cache

import com.circaso.calls.domain.ports.ReportAllowance
import com.circaso.infrastructure.api.RedisScope
import com.circaso.shared.UserId
import java.time.Duration

/**
 * `cal:reports:{userId}`, a counter whose window starts at the person's first report and lasts the window (an hour):
 * support's ticket allowance shape. The key always carries its TTL, so it never outlives the window.
 */
class RedisReportAllowance(private val redis: RedisScope) : ReportAllowance {
    override suspend fun take(
        userId: UserId,
        max: Long,
        window: Duration,
    ): Long? {
        val key = "reports:$userId"
        val count = redis.increment(key, window)
        if (count <= max) return null
        return redis.ttl(key)?.let { (it.toMillis() + MILLIS_IN_SECOND - 1) / MILLIS_IN_SECOND }?.coerceAtLeast(1)
            ?: window.seconds
    }

    private companion object {
        const val MILLIS_IN_SECOND = 1_000
    }
}
