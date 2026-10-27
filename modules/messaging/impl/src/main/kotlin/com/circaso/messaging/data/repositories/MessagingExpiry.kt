package com.circaso.messaging.data.repositories

import com.circaso.infrastructure.api.Database
import java.time.Duration

/**
 * The work of `messaging.purge-expired` (messaging.md → Data): deletes expired `client_messages` in chunks, and keeps
 * the daily partitions one day ahead, dropping the message tables' 30 days and `realtime` and `outbox` 7 days after
 * each one ends. Returns how many rows and partitions it touched.
 */
class MessagingExpiry(private val database: Database) {
    suspend fun run(): Int {
        var items = 0
        for (table in EXPIRING) items += database.purgeExpired(table)
        for ((table, retention) in PARTITIONED) items += database.maintainDailyPartitions(table, retention).count
        return items
    }

    private companion object {
        val EXPIRING = listOf("client_messages")
        val PARTITIONED =
            listOf(
                "inbox" to Duration.ofDays(30),
                "group_messages" to Duration.ofDays(30),
                "message_records" to Duration.ofDays(30),
                "realtime" to Duration.ofDays(7),
                "outbox" to Duration.ofDays(7),
            )
    }
}
