package com.circaso.wallet.data.cache

import com.circaso.infrastructure.api.RedisScope
import com.circaso.shared.UserId
import com.circaso.wallet.domain.ports.DailySpend
import com.circaso.wallet.domain.ports.Reminders
import java.time.Duration
import java.time.LocalDate

/** `wal:sent:{userId}:{date}`: USDC minor units prepared to send on one UTC day, kept 2 days. */
class RedisDailySpend(private val redis: RedisScope) : DailySpend {
    override suspend fun total(userId: UserId, day: LocalDate): Long = redis.get(key(userId, day))?.toLongOrNull() ?: 0

    override suspend fun add(
        userId: UserId,
        day: LocalDate,
        amountMinor: Long,
    ): Long = redis.incrementBy(key(userId, day), amountMinor, TTL)

    private fun key(userId: UserId, day: LocalDate) = "sent:$userId:$day"

    private companion object {
        val TTL: Duration = Duration.ofDays(2)
    }
}

/** `wal:remind:{from}:{to}`: `1` for 24 hours once W10 told the contact. */
class RedisReminders(private val redis: RedisScope) : Reminders {
    override suspend fun claim(from: UserId, to: UserId): Boolean = redis.setIfAbsent("remind:$from:$to", "1", TTL)

    private companion object {
        val TTL: Duration = Duration.ofHours(24)
    }
}
