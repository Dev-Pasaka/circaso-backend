package com.circaso.wallet.data.repositories

import com.circaso.infrastructure.api.Database
import java.time.Duration

/**
 * The work of `wallet.purge-expired` (wallet.md → Data): keeps the daily partitions of `realtime` and `outbox` one
 * day ahead and drops each one 7 days after it ends. `payment_holds` joins it with WS4.
 */
class WalletExpiry(private val database: Database) {
    suspend fun run(): Int = PARTITIONED.sumOf { database.maintainDailyPartitions(it, RETENTION).count }

    private companion object {
        val PARTITIONED = listOf("realtime", "outbox")
        val RETENTION: Duration = Duration.ofDays(7)
    }
}
