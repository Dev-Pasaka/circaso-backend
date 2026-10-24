package com.circaso.infrastructure.impl.redis

import com.circaso.infrastructure.api.HealthCheck
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import io.lettuce.core.ClientOptions
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.MaintNotificationsConfig
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import java.time.Duration

private const val INCREMENT_WITH_TTL = """
local value = redis.call('INCR', KEYS[1])
if value == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
return value
"""

private const val INCREMENT_BY_WITH_TTL = """
local value = redis.call('INCRBY', KEYS[1], ARGV[2])
if value == tonumber(ARGV[2]) then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
return value
"""

private const val INCREMENT_AND_RENEW = """
local value = redis.call('INCR', KEYS[1])
redis.call('PEXPIRE', KEYS[1], ARGV[1])
return value
"""

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

    /** INCR that sets the expiry in the same step, so a crash between the two can't leave a key that never expires. */
    suspend fun incrementWithTtl(key: String, ttl: Duration): Long =
        commands.eval<Long>(INCREMENT_WITH_TTL, ScriptOutputType.INTEGER, arrayOf(key), ttl.toMillis().toString())!!

    suspend fun incrementByWithTtl(
        key: String,
        by: Long,
        ttl: Duration,
    ): Long =
        commands.eval<Long>(
            INCREMENT_BY_WITH_TTL,
            ScriptOutputType.INTEGER,
            arrayOf(key),
            ttl.toMillis().toString(),
            by.toString(),
        )!!

    suspend fun incrementAndRenew(key: String, ttl: Duration): Long =
        commands.eval<Long>(INCREMENT_AND_RENEW, ScriptOutputType.INTEGER, arrayOf(key), ttl.toMillis().toString())!!

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
