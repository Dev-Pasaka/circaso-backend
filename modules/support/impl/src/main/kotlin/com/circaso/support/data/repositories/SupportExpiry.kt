package com.circaso.support.data.repositories

import com.circaso.infrastructure.api.Database
import java.time.Duration

/**
 * The database half of `support.purge-expired`: keeps the daily partitions of `realtime` rolling (today's and
 * tomorrow's made, those older than 7 days dropped) and deletes diagnostic logs past their `expires_at` (30 days,
 * 2026-11-13). Tickets themselves don't expire; they go with the account.
 */
class SupportExpiry(private val database: Database) {
    suspend fun run(): Int =
        database.maintainDailyPartitions("realtime", FRAME_RETENTION).count +
            database.purgeExpired("diagnostic_logs", LOG_CHUNK)

    private companion object {
        val FRAME_RETENTION: Duration = Duration.ofDays(7)

        /** Logs are up to 2 MiB each: a small chunk keeps one delete's transaction short. */
        const val LOG_CHUNK = 200
    }
}
