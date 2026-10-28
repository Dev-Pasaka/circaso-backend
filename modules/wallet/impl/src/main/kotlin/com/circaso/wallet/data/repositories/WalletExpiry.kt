package com.circaso.wallet.data.repositories

import com.circaso.infrastructure.api.Database
import java.time.Duration

/**
 * The work of `wallet.purge-expired` (wallet.md → Data): keeps the outbox's daily partitions one day ahead and drops
 * each one 7 days after it ends. `payment_holds` and `realtime` join it with their slices (WS3, WS4).
 */
class WalletExpiry(private val database: Database) {
    suspend fun run(): Int = database.maintainDailyPartitions("outbox", RETENTION).count

    private companion object {
        val RETENTION: Duration = Duration.ofDays(7)
    }
}
