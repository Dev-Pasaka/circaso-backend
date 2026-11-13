package com.circaso.wallet.data.repositories

import com.circaso.infrastructure.api.Database
import java.time.Duration

/**
 * The work of `wallet.purge-expired` (wallet.md → Data): keeps the daily partitions of `realtime` and `outbox` one
 * day ahead and drops each one 7 days after it ends, and deletes the fee quotes (phase 2F; a W4 that names one then
 * quotes again) and payment holds (2026-11-11) that have run out.
 */
class WalletExpiry(private val database: Database) {
    suspend fun run(): Int =
        PARTITIONED.sumOf { database.maintainDailyPartitions(it, RETENTION).count } +
            database.purgeExpired("fee_quotes") +
            database.purgeExpired("payment_holds")

    private companion object {
        val PARTITIONED = listOf("realtime", "outbox")
        val RETENTION: Duration = Duration.ofDays(7)
    }
}
