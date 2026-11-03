package com.circaso.status.data.cache

import com.circaso.infrastructure.api.RedisScope
import com.circaso.shared.UserId
import com.circaso.status.domain.ports.KeyRequestThrottle
import java.time.Duration

/** `sts:keyreq:{requester}:{poster}`, set with `NX` for the window: the first claim in a window wins. */
class RedisKeyRequestThrottle(private val redis: RedisScope) : KeyRequestThrottle {
    override suspend fun claim(
        requester: UserId,
        poster: UserId,
        window: Duration,
    ): Boolean = redis.setIfAbsent("keyreq:$requester:$poster", "1", window)
}
