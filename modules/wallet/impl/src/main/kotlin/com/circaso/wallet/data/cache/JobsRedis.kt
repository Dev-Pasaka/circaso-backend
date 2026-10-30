package com.circaso.wallet.data.cache

import com.circaso.infrastructure.api.RedisScope
import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.domain.ports.ActiveWallets
import com.circaso.wallet.domain.ports.StuckNotices
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * `wal:active`: one hash, field the user id, value the epoch ms of their last W1 read or W3 session. The key expires
 * [window] after the last write; [since] drops fields older than the cutoff it is asked about, so the hash holds
 * about one window of users.
 */
class RedisActiveWallets(private val redis: RedisScope, private val window: Duration, private val clock: Clock) :
    ActiveWallets {
    private val log = LoggerFactory.getLogger(RedisActiveWallets::class.java)

    override suspend fun touch(userId: UserId) {
        // Only a hint for the reconciliation job: Redis failing must never fail W1 or W3.
        try {
            redis.hashSet(KEY, mapOf(userId.toString() to clock.millis().toString()), window)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("could not mark a wallet active: error={}", e::class.simpleName)
        }
    }

    override suspend fun since(cutoff: Instant): List<UserId> {
        val entries = redis.hashGetAll(KEY)
        val (fresh, stale) = entries.entries.partition { (it.value.toLongOrNull() ?: 0) >= cutoff.toEpochMilli() }
        stale.forEach { redis.hashDelete(KEY, it.key) }
        return fresh
            .sortedByDescending { it.value.toLong() }
            .mapNotNull { UserId.parse(it.key) }
    }

    private companion object {
        const val KEY = "active"
    }
}

/** `wal:stuck:{transferId}`: `1` for 2 days once the transfer was reported stuck. */
class RedisStuckNotices(private val redis: RedisScope) : StuckNotices {
    override suspend fun claim(transferId: TransferId): Boolean = redis.setIfAbsent("stuck:$transferId", "1", TTL)

    private companion object {
        val TTL: Duration = Duration.ofDays(2)
    }
}
