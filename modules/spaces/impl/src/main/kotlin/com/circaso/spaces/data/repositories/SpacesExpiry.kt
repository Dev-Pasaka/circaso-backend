package com.circaso.spaces.data.repositories

import com.circaso.infrastructure.api.Database
import java.time.Duration

/** The database half of `spaces.purge-expired`: keeps the daily partitions of `realtime` rolling, 7 days kept. */
class SpacesExpiry(private val database: Database) {
    suspend fun run(): Int = database.maintainDailyPartitions("realtime", FRAME_RETENTION).count

    private companion object {
        val FRAME_RETENTION: Duration = Duration.ofDays(7)
    }
}
