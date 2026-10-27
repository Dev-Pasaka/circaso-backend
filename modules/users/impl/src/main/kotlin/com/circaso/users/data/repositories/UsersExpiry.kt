package com.circaso.users.data.repositories

import com.circaso.infrastructure.api.Database
import java.time.Duration

/**
 * The work of `users.purge-expired` (users.md → Data): deletes the expired rows of the `expires_at` tables in chunks,
 * and keeps the daily partitions of `realtime` and `outbox` one day ahead, dropping each one 7 days after it ends.
 * Returns how many rows and partitions it touched.
 */
class UsersExpiry(private val database: Database) {
    suspend fun run(): Int {
        var items = 0
        for (table in EXPIRING) items += database.purgeExpired(table)
        for (table in PARTITIONED) items += database.maintainDailyPartitions(table, RETENTION).count
        return items
    }

    private companion object {
        val EXPIRING = listOf("handles", "waiting_contacts", "invites")
        val PARTITIONED = listOf("realtime", "outbox")
        val RETENTION: Duration = Duration.ofDays(7)
    }
}
