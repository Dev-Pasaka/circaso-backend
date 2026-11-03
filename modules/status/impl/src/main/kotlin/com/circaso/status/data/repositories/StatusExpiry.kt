package com.circaso.status.data.repositories

import com.circaso.infrastructure.api.Database
import java.time.Duration

/**
 * The database half of `status.purge-expired`: hard-deletes moments past `expires_at` (their audience and views go
 * with them, by the foreign key), a hundred moments to a transaction since each can carry 1,024 audience rows, and
 * keeps the daily partitions of `realtime` rolling. Their files are the use case's, through storage.
 */
class StatusExpiry(private val database: Database) {
    suspend fun run(): Int =
        database.purgeExpired("moments", MOMENT_CHUNK) +
            database.maintainDailyPartitions("realtime", FRAME_RETENTION).count

    private companion object {
        const val MOMENT_CHUNK = 100
        val FRAME_RETENTION: Duration = Duration.ofDays(7)
    }
}
