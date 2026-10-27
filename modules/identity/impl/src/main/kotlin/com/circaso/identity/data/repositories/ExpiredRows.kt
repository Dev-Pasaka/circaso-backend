package com.circaso.identity.data.repositories

import com.circaso.infrastructure.api.Database
import java.time.Duration

/**
 * `identity.purge-expired` (identity.md → Data): deletes the sessions whose `expires_at` has passed (reads already hide
 * them) and keeps the outbox's daily partitions one day ahead, dropping those older than 7 days. Runs on the jobs pool
 * under the scheduler's lock. Returns how many rows and partitions it changed, for the job's log line.
 */
class ExpiredRows(private val database: Database) {
    suspend fun purge(): Int {
        val sessions = database.purgeExpired("sessions")
        val partitions = database.maintainDailyPartitions("outbox", OUTBOX_RETENTION)
        return sessions + partitions.count
    }

    private companion object {
        val OUTBOX_RETENTION: Duration = Duration.ofDays(7)
    }
}
