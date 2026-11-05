package com.circaso.wallet.data.cache

import com.circaso.infrastructure.api.RedisScope
import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.domain.ports.ActiveWallets
import com.circaso.wallet.domain.ports.PendingSetups
import com.circaso.wallet.domain.ports.SETUP_CHECK_WINDOW
import com.circaso.wallet.domain.ports.SETUP_SWEEP_WINDOW
import com.circaso.wallet.domain.ports.SetupChecks
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

/**
 * `wal:setupcheck:{holder}`: `1` for [SETUP_CHECK_WINDOW] after a wallet being set up was read from Circle. Redis
 * failing answers no: the read falls back to our rows, never fails, and the webhook and the next poll still come.
 */
class RedisSetupChecks(private val redis: RedisScope) : SetupChecks {
    private val log = LoggerFactory.getLogger(RedisSetupChecks::class.java)

    override suspend fun claim(holder: UserId): Boolean =
        try {
            redis.setIfAbsent("setupcheck:$holder", "1", SETUP_CHECK_WINDOW)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("wallet setup check not claimed: {}", e.javaClass.simpleName)
            false
        }
}

/**
 * `wal:setup:{holder}` (the challenge handed out) and `wal:setups` (field the holder, value the epoch ms the setup
 * began), both for [SETUP_SWEEP_WINDOW] from the last write. Writing never fails the setup: without it the next tap
 * starts a fresh challenge, as before, and the webhook and the polls still record the wallet.
 */
class RedisPendingSetups(private val redis: RedisScope, private val clock: Clock) : PendingSetups {
    private val log = LoggerFactory.getLogger(RedisPendingSetups::class.java)

    override suspend fun put(holder: UserId, challengeId: String) {
        quietly("record") {
            redis.set(key(holder), challengeId, SETUP_SWEEP_WINDOW)
            redis.hashSet(SETUPS, mapOf(holder.toString() to clock.millis().toString()), SETUP_SWEEP_WINDOW)
        }
    }

    override suspend fun challenge(holder: UserId): String? = quietly("read") { redis.get(key(holder)) }

    override suspend fun since(cutoff: Instant): List<UserId> {
        val entries = redis.hashGetAll(SETUPS)
        val (fresh, stale) = entries.entries.partition { (it.value.toLongOrNull() ?: 0) >= cutoff.toEpochMilli() }
        stale.forEach { redis.hashDelete(SETUPS, it.key) }
        return fresh.sortedByDescending { it.value.toLong() }.mapNotNull { UserId.parse(it.key) }
    }

    override suspend fun clear(holder: UserId) {
        quietly("clear") {
            redis.delete(key(holder))
            redis.hashDelete(SETUPS, holder.toString())
        }
    }

    private suspend fun <T> quietly(what: String, block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("wallet setup not {}: error={}", what, e::class.simpleName)
            null
        }

    private fun key(holder: UserId) = "setup:$holder"

    private companion object {
        const val SETUPS = "setups"
    }
}
