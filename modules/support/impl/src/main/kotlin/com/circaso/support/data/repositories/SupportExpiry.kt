package com.circaso.support.data.repositories

import com.circaso.infrastructure.api.Database
import java.time.Duration

/**
 * The database half of `support.purge-expired`: keeps the daily partitions of `realtime` rolling (today's and
 * tomorrow's made, those older than 7 days dropped). Tickets themselves don't expire; they go with the account.
 */
class SupportExpiry(private val database: Database) {
    suspend fun run(): Int = database.maintainDailyPartitions("realtime", FRAME_RETENTION).count

    private companion object {
        val FRAME_RETENTION: Duration = Duration.ofDays(7)
    }
}
