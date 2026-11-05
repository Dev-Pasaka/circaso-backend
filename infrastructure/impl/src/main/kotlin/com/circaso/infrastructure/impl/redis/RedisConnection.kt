package com.circaso.infrastructure.impl.redis

import com.circaso.infrastructure.api.HealthCheck
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import io.lettuce.core.ClientOptions
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.MaintNotificationsConfig
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisNoScriptException
import io.lettuce.core.RedisURI
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.api.coroutines
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import java.security.MessageDigest
import java.time.Duration

private val INCREMENT_WITH_TTL =
    RedisScript(
        """
local value = redis.call('INCR', KEYS[1])
if value == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
return value
""",
    )

private val INCREMENT_BY_WITH_TTL =
    RedisScript(
        """
local value = redis.call('INCRBY', KEYS[1], ARGV[2])
if value == tonumber(ARGV[2]) then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
return value
""",
    )

private val INCREMENT_AND_RENEW =
    RedisScript(
        """
local value = redis.call('INCR', KEYS[1])
redis.call('PEXPIRE', KEYS[1], ARGV[1])
return value
""",
    )

/**
 * A Lua script sent by its SHA-1 (`EVALSHA`), not its text: the rate limiter runs one on every request. Valkey keeps
 * scripts it has seen, so the text goes out once per server (and again after a restart or `SCRIPT FLUSH`, when the
 * server answers `NOSCRIPT` and [RedisConnection.run] falls back to `EVAL`, which loads it).
 */
class RedisScript(val source: String) {
    val sha: String =
        MessageDigest
            .getInstance("SHA-1")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/**
 * The one Redis client of the process, with a shared connection for ordinary commands. Blocking reads (the
 * stream consumers) must not share it, so [openDedicated] hands out a connection of their own.
 * Lettuce stays inside infrastructure: modules see only `RedisScope`.
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class RedisConnection(url: String) : AutoCloseable {
    private val client: RedisClient =
        RedisClient.create(RedisURI.create(url).also { it.timeout = Duration.ofSeconds(5) }).also {
            // Lettuce 7 greets the server with `CLIENT MAINT_NOTIFICATIONS`, which Redis Enterprise understands and
            // Valkey and open-source Redis answer with "unknown subcommand". Nothing here needs maintenance events.
            it.options = ClientOptions.builder().maintNotificationsConfig(MaintNotificationsConfig.disabled()).build()
        }
    private val shared: StatefulRedisConnection<String, String> = client.connect()

    val commands: RedisCoroutinesCommands<String, String> = shared.coroutines()

    /** The same connection, for commands sent back to back without waiting for each reply (a pipeline). */
    val async: RedisAsyncCommands<String, String> = shared.async()

    /** Runs [script] by its SHA-1; if the server doesn't have it yet, sends the text once, which also loads it. */
    suspend fun <T> run(
        script: RedisScript,
        type: ScriptOutputType,
        keys: Array<String>,
        vararg values: String,
    ): T? =
        try {
            commands.evalsha<T>(script.sha, type, keys, *values)
        } catch (e: RedisNoScriptException) {
            commands.eval<T>(script.source, type, keys, *values)
        }

    /** INCR that sets the expiry in the same step, so a crash between the two can't leave a key that never expires. */
    suspend fun incrementWithTtl(key: String, ttl: Duration): Long =
        run<Long>(INCREMENT_WITH_TTL, ScriptOutputType.INTEGER, arrayOf(key), ttl.toMillis().toString())!!

    suspend fun incrementByWithTtl(
        key: String,
        by: Long,
        ttl: Duration,
    ): Long =
        run<Long>(
            INCREMENT_BY_WITH_TTL,
            ScriptOutputType.INTEGER,
            arrayOf(key),
            ttl.toMillis().toString(),
            by.toString(),
        )!!

    suspend fun incrementAndRenew(key: String, ttl: Duration): Long =
        run<Long>(INCREMENT_AND_RENEW, ScriptOutputType.INTEGER, arrayOf(key), ttl.toMillis().toString())!!

    /** A connection nobody else uses, for blocking commands. The caller closes it. */
    fun openDedicated(): StatefulRedisConnection<String, String> = client.connect()

    /** A pub/sub connection for one subscriber. The caller closes it. */
    fun openPubSub(): StatefulRedisPubSubConnection<String, String> = client.connectPubSub()

    override fun close() {
        shared.close()
        client.shutdown()
    }
}

@OptIn(ExperimentalLettuceCoroutinesApi::class)
class RedisHealth(private val redis: RedisConnection) : HealthCheck {
    override val name = "redis"

    override suspend fun check(): Resource<Unit> =
        if (redis.commands.ping() == "PONG") Resource.success(Unit) else Resource.error(SharedError.Internal)
}
