package com.circaso.infrastructure.api

import kotlinx.coroutines.flow.Flow
import java.time.Duration

/**
 * Redis for one module, behind a key prefix (`idn:`, `usr:`). A module never sees another module's keys, and
 * every key it writes has an expiry: there is no write without a TTL.
 *
 * Keys are `<purpose>:<id>` (`otp:0193…`); the scope puts the module prefix in front.
 */
interface RedisScope {
    val prefix: String

    suspend fun get(key: String): String?

    /** `MGET`: one round trip for many keys. The values come back in the order of [keys], null where a key is missing. */
    suspend fun getMany(keys: List<String>): List<String?>

    suspend fun set(
        key: String,
        value: String,
        ttl: Duration,
    )

    /** `SET NX`: true if this call created the key. Used for claims, locks and "first time seen" markers. */
    suspend fun setIfAbsent(
        key: String,
        value: String,
        ttl: Duration,
    ): Boolean

    /** Reads and deletes in one step, so a single-use value (a nonce) can't be used twice. */
    suspend fun getAndDelete(key: String): String?

    suspend fun delete(key: String): Boolean

    suspend fun exists(key: String): Boolean

    /** Adds one to a counter, creating it with [ttl] on first use; the expiry is not extended by later calls. */
    suspend fun increment(key: String, ttl: Duration): Long

    /** Adds [by] to a counter, creating it with [ttl] on first use; like [increment], the expiry is not extended. */
    suspend fun incrementBy(
        key: String,
        by: Long,
        ttl: Duration,
    ): Long

    /** Like [increment], but every call pushes the expiry out to [ttl] again: a counter that must outlive its use (a `seq`). */
    suspend fun incrementRenewing(key: String, ttl: Duration): Long

    suspend fun hashSet(
        key: String,
        fields: Map<String, String>,
        ttl: Duration,
    )

    suspend fun hashGetAll(key: String): Map<String, String>

    /**
     * Adds [by] to one field of a hash that already exists; the hash keeps its expiry. Returns null when the
     * hash is gone (expired), rather than creating one that would never expire.
     */
    suspend fun hashIncrement(
        key: String,
        field: String,
        by: Long = 1,
    ): Long?

    /** Time left, or null when the key doesn't exist. */
    suspend fun ttl(key: String): Duration?

    /** Publishes [message] on the pub/sub channel `<prefix>:<channel>`. Fire and forget: nobody listening means it is dropped. */
    suspend fun publish(channel: String, message: String)

    /**
     * The messages published on `<prefix>:<channel>` from the moment collection starts until it is cancelled.
     * Used for realtime frames between nodes; anything a listener misses is recovered from the module's data.
     */
    fun subscribe(channel: String): Flow<String>
}

/** Hands each module its own [RedisScope]. */
interface RedisScopes {
    /** [prefix] is the module's short Redis prefix: lowercase letters, 2 to 8 of them (`idn`, `usr`). */
    fun scope(prefix: String): RedisScope
}
