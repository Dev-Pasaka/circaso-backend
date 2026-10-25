package com.circaso.infrastructure.impl.redis

import com.circaso.infrastructure.api.RedisScope
import com.circaso.infrastructure.api.RedisScopes
import com.circaso.infrastructure.impl.logging.kv
import com.circaso.infrastructure.impl.logging.shortenIds
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.SetArgs
import io.lettuce.core.pubsub.RedisPubSubAdapter
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import java.time.Duration

private val log = LoggerFactory.getLogger("com.circaso.infrastructure.impl.redis.Redis")

private const val SLOW_REDIS_MILLIS = 20L

// HSET plus PEXPIRE as one step.
private const val HASH_SET_WITH_TTL = """
for i = 2, #ARGV, 2 do redis.call('HSET', KEYS[1], ARGV[i], ARGV[i + 1]) end
redis.call('PEXPIRE', KEYS[1], ARGV[1])
return 1
"""

// HINCRBY only on a hash that exists; otherwise it would create one with no expiry.
private const val HASH_INCREMENT_IF_EXISTS = """
if redis.call('EXISTS', KEYS[1]) == 0 then return false end
return redis.call('HINCRBY', KEYS[1], ARGV[1], ARGV[2])
"""

@OptIn(ExperimentalLettuceCoroutinesApi::class)
class LettuceRedisScopes(private val redis: RedisConnection) : RedisScopes {
    override fun scope(prefix: String): RedisScope {
        require(Regex("[a-z]{2,8}").matches(prefix)) { "A Redis prefix is 2 to 8 lowercase letters (was '$prefix')" }
        return LettuceRedisScope(prefix, redis)
    }
}

@OptIn(ExperimentalLettuceCoroutinesApi::class)
class LettuceRedisScope(override val prefix: String, private val redis: RedisConnection) : RedisScope {
    private val commands get() = redis.commands

    private fun key(key: String) = "$prefix:$key"

    private suspend fun <T> timed(
        command: String,
        key: String,
        block: suspend () -> T,
    ): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            val millis = (System.nanoTime() - start) / 1_000_000
            val fields =
                arrayOf(
                    kv("kind", "redis"),
                    kv("command", command),
                    kv("key", shortenIds(key(key))),
                    kv("durationMs", millis),
                )
            if (millis > SLOW_REDIS_MILLIS) log.warn("slow", *fields) else log.debug("redis", *fields)
        }
    }

    override suspend fun get(key: String): String? = timed("GET", key) { commands.get(key(key)) }

    override suspend fun getPipelined(keys: List<String>): List<String?> {
        if (keys.isEmpty()) return emptyList()
        return timed("GET*${keys.size}", keys.first()) {
            // Every GET is written before any reply is awaited: Lettuce pipelines them on the shared connection.
            val replies = keys.map { redis.async.get(key(it)) }
            replies.map { it.await() }
        }
    }

    override suspend fun set(
        key: String,
        value: String,
        ttl: Duration,
    ) = timed("SET", key) {
        commands.set(key(key), value, SetArgs().px(ttl.toMillis()))
        Unit
    }

    override suspend fun setIfAbsent(
        key: String,
        value: String,
        ttl: Duration,
    ): Boolean =
        timed("SETNX", key) {
            commands.set(key(key), value, SetArgs().nx().px(ttl.toMillis())) != null
        }

    override suspend fun getAndDelete(key: String): String? = timed("GETDEL", key) { commands.getdel(key(key)) }

    override suspend fun delete(key: String): Boolean = timed("DEL", key) { (commands.del(key(key)) ?: 0) > 0 }

    override suspend fun exists(key: String): Boolean = timed("EXISTS", key) { (commands.exists(key(key)) ?: 0) > 0 }

    override suspend fun increment(key: String, ttl: Duration): Long =
        timed("INCR", key) {
            redis.incrementWithTtl(key(key), ttl)
        }

    override suspend fun incrementBy(
        key: String,
        by: Long,
        ttl: Duration,
    ): Long =
        timed("INCRBY", key) {
            redis.incrementByWithTtl(key(key), by, ttl)
        }

    override suspend fun incrementRenewing(key: String, ttl: Duration): Long =
        timed("INCR", key) {
            redis.incrementAndRenew(key(key), ttl)
        }

    override suspend fun hashSet(
        key: String,
        fields: Map<String, String>,
        ttl: Duration,
    ) = timed("HSET", key) {
        require(fields.isNotEmpty()) { "A hash needs at least one field" }
        val args =
            buildList {
                add(ttl.toMillis().toString())
                fields.forEach { (field, value) ->
                    add(field)
                    add(value)
                }
            }
        commands.eval<Long>(HASH_SET_WITH_TTL, ScriptOutputType.INTEGER, arrayOf(key(key)), *args.toTypedArray())
        Unit
    }

    override suspend fun hashGetAll(key: String): Map<String, String> =
        timed("HGETALL", key) {
            commands.hgetall(key(key)).toList().associate { it.key to it.value }
        }

    override suspend fun hashIncrement(
        key: String,
        field: String,
        by: Long,
    ): Long? =
        timed("HINCRBY", key) {
            commands.eval<Long>(
                HASH_INCREMENT_IF_EXISTS,
                ScriptOutputType.INTEGER,
                arrayOf(key(key)),
                field,
                by.toString(),
            )
        }

    override suspend fun ttl(key: String): Duration? =
        timed("PTTL", key) {
            when (val millis = commands.pttl(key(key))) {
                null, -2L -> null

                -1L -> Duration.ZERO

                // exists without an expiry; the scope never creates such a key
                else -> Duration.ofMillis(millis)
            }
        }

    override suspend fun publish(channel: String, message: String) =
        timed("PUBLISH", channel) {
            commands.publish(key(channel), message)
            Unit
        }

    override fun subscribe(channel: String): Flow<String> =
        callbackFlow {
            val name = key(channel)
            val connection = redis.openPubSub()
            connection.addListener(
                object : RedisPubSubAdapter<String, String>() {
                    override fun message(channel: String, message: String) {
                        trySend(message)
                    }
                },
            )
            connection.async().subscribe(name).await()
            awaitClose { connection.closeAsync() }
        }
}
