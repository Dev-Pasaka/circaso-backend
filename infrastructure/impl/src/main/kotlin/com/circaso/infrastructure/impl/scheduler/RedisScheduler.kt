package com.circaso.infrastructure.impl.scheduler

import com.circaso.infrastructure.api.Scheduler
import com.circaso.infrastructure.impl.logging.kv
import com.circaso.infrastructure.impl.logging.millisSince
import com.circaso.infrastructure.impl.redis.RedisConnection
import com.circaso.observability.api.NoopTelemetry
import com.circaso.observability.api.Telemetry
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.SetArgs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

private val log = LoggerFactory.getLogger("com.circaso.infrastructure.impl.scheduler.Scheduler")

// Compare-and-act on the lock, so a node never renews or deletes a lock another node has since taken.
private const val RENEW =
    "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('PEXPIRE', KEYS[1], " +
        "ARGV[2]) else return 0 end"
private const val RELEASE =
    "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else " +
        "return 0 end"

/**
 * Every node runs this, and a Redis lock per job lets one node at a time run it. A job's lock lasts one
 * interval and is not released when the job finishes early, so the job runs at most once per interval across
 * all nodes; while a job runs, the lock is renewed, so a slow run is never joined by a second node.
 * A node that dies mid-job simply stops renewing: the lock lapses and another node takes over.
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class RedisScheduler(
    private val redis: RedisConnection,
    private val telemetry: Telemetry = NoopTelemetry,
    private val nodeId: String = UUID.randomUUID().toString(),
) : Scheduler,
    AutoCloseable {
    private class Definition(val name: String, val interval: Duration, val job: suspend () -> Int)

    private val definitions = CopyOnWriteArrayList<Definition>()
    private val loops = CopyOnWriteArrayList<Job>()

    @Volatile
    private var scope: CoroutineScope? = null

    override fun every(
        name: String,
        interval: Duration,
        job: suspend () -> Int,
    ) {
        val definition = Definition(name, interval, job)
        definitions += definition
        scope?.let { launchLoop(it, definition) }
    }

    /** Starts every registered job, and any registered later. */
    fun start(scope: CoroutineScope) {
        this.scope = scope
        definitions.forEach { launchLoop(scope, it) }
    }

    override fun close() {
        loops.forEach { it.cancel() }
        scope = null
    }

    override suspend fun <T> withLock(
        name: String,
        lease: Duration,
        block: suspend () -> T,
    ): T? {
        if (!acquire(name, lease)) return null
        return try {
            holding(name, lease, block)
        } finally {
            runCatching { redis.commands.eval<Long>(RELEASE, ScriptOutputType.INTEGER, arrayOf(lockKey(name)), nodeId) }
        }
    }

    private fun launchLoop(scope: CoroutineScope, definition: Definition) {
        loops +=
            scope.launch {
                // Nodes that start together shouldn't all tick in the same instant.
                delay(Random.nextLong(0, definition.interval.toMillis() / 4 + 1))
                while (isActive) {
                    tick(definition)
                    delay(definition.interval.toMillis())
                }
            }
    }

    private suspend fun tick(definition: Definition) {
        // The lock lasts one interval, so no job runs more often than that.
        val lease = definition.interval.coerceAtLeast(MIN_LEASE)
        try {
            if (!acquire(definition.name, lease)) return
            val start = System.nanoTime()
            log.info("start", kv("kind", "job"), kv("job", definition.name))
            val items =
                holding(definition.name, lease) { telemetry.span("job.${definition.name}") { definition.job() } }
            log.info(
                "done",
                kv("kind", "job"),
                kv("job", definition.name),
                kv("items", items),
                kv("durationMs", 0L.millisSince(start)),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("failed", kv("kind", "job"), kv("job", definition.name), e)
        }
    }

    private suspend fun acquire(name: String, lease: Duration): Boolean =
        redis.commands.set(lockKey(name), nodeId, SetArgs().nx().px(lease.toMillis())) != null

    /** Runs [block] while a sibling coroutine keeps the lock alive. */
    private suspend fun <T> holding(
        name: String,
        lease: Duration,
        block: suspend () -> T,
    ): T =
        coroutineScope {
            val renewal =
                launch {
                    while (isActive) {
                        delay(lease.toMillis() / 3)
                        redis.commands.eval<Long>(
                            RENEW,
                            ScriptOutputType.INTEGER,
                            arrayOf(lockKey(name)),
                            nodeId,
                            lease.toMillis().toString(),
                        )
                    }
                }
            try {
                block()
            } finally {
                renewal.cancel()
            }
        }

    private fun lockKey(name: String) = "sch:lock:$name"

    private companion object {
        /** A floor so a very short interval can't make the renewal loop spin. */
        val MIN_LEASE: Duration = Duration.ofMillis(300)
    }
}
