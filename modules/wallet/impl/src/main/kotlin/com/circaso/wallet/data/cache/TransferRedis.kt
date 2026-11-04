package com.circaso.wallet.data.cache

import com.circaso.infrastructure.api.RedisScope
import com.circaso.shared.UserId
import com.circaso.wallet.domain.ports.Reminders
import java.time.Duration

/** `wal:remind:{from}:{to}`: `1` for 24 hours once W10 told the contact. */
class RedisReminders(private val redis: RedisScope) : Reminders {
    override suspend fun claim(from: UserId, to: UserId): Boolean = redis.setIfAbsent("remind:$from:$to", "1", TTL)

    private companion object {
        val TTL: Duration = Duration.ofHours(24)
    }
}
