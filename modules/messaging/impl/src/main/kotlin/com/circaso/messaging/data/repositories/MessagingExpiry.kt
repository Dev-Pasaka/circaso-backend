package com.circaso.messaging.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.messaging.data.tables.ChatHeadsTable
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * The work of `messaging.purge-expired` (messaging.md → Data): deletes expired `client_messages` in chunks, and keeps
 * the daily partitions one day ahead, dropping the message tables' 30 days and `realtime` and `outbox` 7 days after
 * each one ends. Once `message_records` partitions go, the `chat_heads` pointing into them go too, so a chat quiet
 * for 30 days shows no last message, as when the list read the records. Returns how many rows and partitions it
 * touched.
 */
class MessagingExpiry(private val database: Database) {
    suspend fun run(): Int {
        var items = 0
        for (table in EXPIRING) items += database.purgeExpired(table)
        for ((table, retention) in PARTITIONED) {
            val changes = database.maintainDailyPartitions(table, retention)
            items += changes.count
            if (table == RECORDS) items += forgetHeads(changes.dropped)
        }
        return items
    }

    /**
     * Deletes the heads whose message lived in one of [dropped] (`message_records_p20261001`) or any earlier day: every
     * id below the lowest UUIDv7 of the day after the newest of them. A range scan of `chat_heads_last_message_idx`.
     */
    private suspend fun forgetHeads(dropped: List<String>): Int {
        val newest = dropped.mapNotNull(::dayOf).maxOrNull() ?: return 0
        val below = lowestUuid7(newest.plusDays(1))
        return database.tx("chats.forget-heads") { ChatHeadsTable.deleteWhere { lastMessageId less below } }
    }

    private fun dayOf(partition: String): LocalDate? =
        partition
            .takeIf { it.startsWith("${RECORDS}_p") }
            ?.removePrefix("${RECORDS}_p")
            ?.let { runCatching { LocalDate.parse(it, DAY) }.getOrNull() }

    /** The smallest UUIDv7 of midnight UTC on [day]: the bound that day's partition starts at. */
    private fun lowestUuid7(day: LocalDate): UUID {
        val millis = day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        return UUID((millis shl 16) or 0x7000L, Long.MIN_VALUE)
    }

    private companion object {
        const val RECORDS = "message_records"
        val DAY: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
        val EXPIRING = listOf("client_messages")
        val PARTITIONED =
            listOf(
                "inbox" to Duration.ofDays(30),
                "group_messages" to Duration.ofDays(30),
                RECORDS to Duration.ofDays(30),
                "realtime" to Duration.ofDays(7),
                "outbox" to Duration.ofDays(7),
            )
    }
}
