package com.circaso.messaging

import com.circaso.infrastructure.api.RedisScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** One module's Redis in memory, remembering the expiry each key was last given so tests can check it. */
class MemoryRedis(override val prefix: String = "msg") : RedisScope {
    val values = ConcurrentHashMap<String, String>()
    val ttls = ConcurrentHashMap<String, Duration>()

    override suspend fun get(key: String): String? = values[key]

    override suspend fun getPipelined(keys: List<String>): List<String?> = keys.map { values[it] }

    override suspend fun set(
        key: String,
        value: String,
        ttl: Duration,
    ) {
        values[key] = value
        ttls[key] = ttl
    }

    override suspend fun setIfAbsent(
        key: String,
        value: String,
        ttl: Duration,
    ): Boolean {
        val created = values.putIfAbsent(key, value) == null
        if (created) ttls[key] = ttl
        return created
    }

    override suspend fun getAndDelete(key: String): String? = values.remove(key)

    override suspend fun delete(key: String): Boolean = values.remove(key) != null

    override suspend fun exists(key: String): Boolean = values.containsKey(key)

    override suspend fun increment(key: String, ttl: Duration): Long = incrementBy(key, 1, ttl)

    override suspend fun incrementBy(
        key: String,
        by: Long,
        ttl: Duration,
    ): Long {
        ttls.putIfAbsent(key, ttl)
        return values.merge(key, by.toString()) { old, _ -> (old.toLong() + by).toString() }!!.toLong()
    }

    override suspend fun incrementRenewing(key: String, ttl: Duration): Long {
        val next = values.merge(key, "1") { old, _ -> (old.toLong() + 1).toString() }!!.toLong()
        ttls[key] = ttl
        return next
    }

    override suspend fun hashSet(
        key: String,
        fields: Map<String, String>,
        ttl: Duration,
    ) = error("not used by messaging")

    override suspend fun hashGetAll(key: String): Map<String, String> = error("not used by messaging")

    override suspend fun hashIncrement(
        key: String,
        field: String,
        by: Long,
    ): Long? = error("not used by messaging")

    override suspend fun ttl(key: String): Duration? = if (values.containsKey(key)) ttls[key] else null

    override suspend fun publish(channel: String, message: String) = Unit

    override fun subscribe(channel: String): Flow<String> = emptyFlow()
}
