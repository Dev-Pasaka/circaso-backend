package com.circaso.assistant.data.repositories

import com.circaso.infrastructure.api.Database
import java.time.Duration

/**
 * The work of `assistant.purge-expired` (assistant.md → Data): keeps the daily partitions of `outbox` one day ahead and
 * drops each one 7 days after it ends. No assistant table has an `expires_at` yet; Phase B's `proposals` will, and is
 * purged here. Returns how many partitions it touched.
 */
class AssistantExpiry(private val database: Database) {
    suspend fun run(): Int = database.maintainDailyPartitions("outbox", RETENTION).count

    private companion object {
        val RETENTION: Duration = Duration.ofDays(7)
    }
}
