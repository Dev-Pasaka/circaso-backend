package com.circaso.ramp

import com.circaso.infrastructure.api.RedisScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * The ramp's Redis in memory, for the keys the trading session writes: plain values that expire on [clock], as the
 * real ones do. [flush] stands in for Redis losing everything.
 */
class MemoryRedis(private val clock: Clock, override val prefix: String = "rmp") : RedisScope {
    private class Entry(val value: String, val expiresAt: Instant)

    private val entries = mutableMapOf<String, Entry>()

    private fun live(key: String): Entry? {
        val entry = entries[key] ?: return null
        if (!entry.expiresAt.isAfter(clock.instant())) {
            entries.remove(key)
            return null
        }
        return entry
    }

    /** The raw value, for tests that tamper with it. */
    fun raw(key: String): String? = live(key)?.value

    fun put(key: String, value: String) {
        val entry = live(key) ?: error("no $key")
        entries[key] = Entry(value, entry.expiresAt)
    }

    fun flush() = entries.clear()

    override suspend fun get(key: String): String? = live(key)?.value

    override suspend fun getPipelined(keys: List<String>): List<String?> = keys.map { live(it)?.value }

    override suspend fun set(
        key: String,
        value: String,
        ttl: Duration,
    ) {
        entries[key] = Entry(value, clock.instant().plus(ttl))
    }

    override suspend fun setIfAbsent(
        key: String,
        value: String,
        ttl: Duration,
    ): Boolean {
        if (live(key) != null) return false
        set(key, value, ttl)
        return true
    }

    override suspend fun getAndDelete(key: String): String? = live(key)?.value.also { entries.remove(key) }

    override suspend fun delete(key: String): Boolean = live(key) != null && entries.remove(key) != null

    override suspend fun exists(key: String): Boolean = live(key) != null

    override suspend fun ttl(key: String): Duration? =
        live(key)?.let { Duration.between(clock.instant(), it.expiresAt) }

    override suspend fun increment(key: String, ttl: Duration): Long = error("not used")

    override suspend fun incrementBy(
        key: String,
        by: Long,
        ttl: Duration,
    ): Long = error("not used")

    override suspend fun incrementRenewing(key: String, ttl: Duration): Long = error("not used")

    override suspend fun hashSet(
        key: String,
        fields: Map<String, String>,
        ttl: Duration,
    ) = error("not used")

    override suspend fun hashGetAll(key: String): Map<String, String> = error("not used")

    override suspend fun hashTake(key: String): Map<String, String> = error("not used")

    override suspend fun hashDelete(key: String, field: String): Boolean = error("not used")

    override suspend fun hashIncrement(
        key: String,
        field: String,
        by: Long,
    ): Long? = error("not used")

    override suspend fun publish(channel: String, message: String) = Unit

    override fun subscribe(channel: String): Flow<String> = emptyFlow()
}
