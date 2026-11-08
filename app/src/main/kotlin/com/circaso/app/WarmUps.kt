package com.circaso.app

import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.RedisScope
import com.circaso.infrastructure.api.RedisScopes
import com.circaso.infrastructure.api.WarmUp
import com.circaso.infrastructure.api.WarmingUp
import com.circaso.shared.CircasoJson
import com.circaso.web.DefaultResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.serializer
import net.logstash.logback.argument.StructuredArguments.kv
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

private val log = LoggerFactory.getLogger("com.circaso.app.WarmUp")

/**
 * Whether the process may take traffic: false until the warm-up has finished (or run out of time). `/health/ready`
 * answers `503` until then, so the deploy and the load balancer wait for a warm process.
 */
class Readiness(ready: Boolean = false) {
    private val ready = AtomicBoolean(ready)

    val isReady: Boolean get() = ready.get()

    fun markReady() = ready.set(true)
}

/**
 * How long and how hard the warm-up runs. `WARMUP_ENABLED` (default true); `WARMUP_WORKERS`, how many run the passes
 * at once (default the CPUs, at least 2 and at most the app pool's size: more than the CPUs only queue behind each other,
 * deploy log 2026-11-05: 10 workers on 4 vCPUs ran out of time after 190 passes); `WARMUP_WIDE_WORKERS`, how many run
 * the one wide pass first (default the app pool's size, so every pooled connection runs the hot paths at least once);
 * `WARMUP_ROUNDS`, passes per worker after it (default 20); and `WARMUP_BUDGET_MS`, at most this long in all (default
 * 15 s; past it the process is ready anyway).
 */
data class WarmUpSettings(
    val enabled: Boolean = true,
    val workers: Int = 4,
    val wideWorkers: Int = 10,
    val rounds: Int = 20,
    val budget: Duration = Duration.ofSeconds(15),
) {
    companion object {
        fun from(config: Config, processors: Int = Runtime.getRuntime().availableProcessors()): WarmUpSettings {
            val section = config.section("warmup")
            // The same setting (and default) as infrastructure's app pool.
            val poolSize =
                config
                    .section("database")
                    .section("pool")
                    .int("app-size", 10)
                    .coerceAtLeast(1)
            return WarmUpSettings(
                enabled = section.boolean("enabled", true),
                workers = section.int("workers", processors.coerceIn(2, maxOf(2, poolSize))).coerceIn(1, 64),
                wideWorkers = section.int("wide-workers", poolSize).coerceIn(0, 64),
                rounds = section.int("rounds", 20).coerceAtLeast(0),
                budget = Duration.ofMillis(section.int("budget-ms", 15_000).coerceAtLeast(0).toLong()),
            )
        }
    }
}

/** What a warm-up did: how many passes ran, whether it ran out of time, and which warm-ups failed (with the exception class). */
data class WarmUpOutcome(val passes: Int, val timedOut: Boolean, val failed: Map<String, String>)

/**
 * Runs the warm-ups within [WarmUpSettings.budget], every call in the [WarmingUp] context (so infrastructure keeps its
 * slow-call lines at DEBUG while the CPU is busy compiling). First each [WarmUp.once] warm-up, one after the other;
 * then one wide pass of the others on [WarmUpSettings.wideWorkers] coroutines at once, so every pooled connection runs
 * the hot paths; then [WarmUpSettings.rounds] passes on each of [WarmUpSettings.workers]. A warm-up that throws is
 * logged once (its exception's class, never its message, which can carry a value) and not called again: a warm-up is
 * never worth a retry loop against a dependency that is down. One line at the end: passes, each warm-up's median and
 * slowest pass, failures, and whether it ran out of time.
 */
class WarmUpRunner(private val warmUps: List<WarmUp>, private val settings: WarmUpSettings) {
    private val failed = ConcurrentHashMap<String, String>()
    private val timings = ConcurrentHashMap<String, MutableList<Long>>()

    suspend fun run(): WarmUpOutcome {
        if (!settings.enabled || warmUps.isEmpty()) return WarmUpOutcome(0, timedOut = false, failed = emptyMap())
        val started = System.nanoTime()
        val passes = AtomicInteger()
        val (once, repeated) = warmUps.partition { it.once }
        val finished =
            withTimeoutOrNull(settings.budget.toMillis().milliseconds) {
                withContext(WarmingUp) {
                    once.forEach { call(it) }
                    if (repeated.isNotEmpty()) {
                        passes(repeated, settings.wideWorkers, rounds = 1, passes)
                        passes(repeated, settings.workers, settings.rounds, passes)
                    }
                }
            } != null
        val outcome = WarmUpOutcome(passes.get(), timedOut = !finished, failed = failed.toMap())
        val fields =
            arrayOf(
                kv("kind", "warmup"),
                kv("durationMs", (System.nanoTime() - started) / 1_000_000),
                kv("passes", outcome.passes),
                kv("workers", settings.workers),
                kv("timings", summary()),
                kv("failed", outcome.failed.keys.sorted()),
                kv("outOfTime", outcome.timedOut),
            )
        if (outcome.timedOut) {
            log.warn("Warm-up ran out of time; ready anyway", *fields)
        } else {
            log.info("Warm-up done", *fields)
        }
        return outcome
    }

    private suspend fun passes(
        repeated: List<WarmUp>,
        workers: Int,
        rounds: Int,
        passes: AtomicInteger,
    ) = coroutineScope {
        repeat(workers) {
            launch {
                repeat(rounds) {
                    repeated.forEach { call(it) }
                    passes.incrementAndGet()
                }
            }
        }
    }

    private suspend fun call(warmUp: WarmUp) {
        if (failed.containsKey(warmUp.name)) return
        val start = System.nanoTime()
        try {
            warmUp.run()
            val took = (System.nanoTime() - start) / 1_000_000
            timings.computeIfAbsent(warmUp.name) { Collections.synchronizedList(mutableListOf()) }.add(took)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (failed.putIfAbsent(warmUp.name, e::class.simpleName ?: "Exception") == null) {
                log.warn("Warm-up '${warmUp.name}' failed (${e::class.simpleName}); skipping it")
            }
        }
    }

    /** `messaging=p50 12ms max 140ms`, per warm-up, in name order. */
    private fun summary(): String =
        timings.entries
            .sortedBy { it.key }
            .joinToString(", ") { (name, all) ->
                val sorted = synchronized(all) { all.sorted() }
                "$name=p50 ${sorted[sorted.size / 2]}ms max ${sorted.last()}ms"
            }
}

/** The app's own warm-up, beside the business modules' (each binds its own in its Koin module). */
val appWarmUpModule =
    module {
        single<WarmUp>(named("infrastructure.warm-up")) {
            InfrastructureWarmUp(get<RedisScopes>().scope(InfrastructureWarmUp.REDIS_PREFIX))
        }
    }

/**
 * Redis's hot commands (`GET`, `SET`, `EXISTS`, the pipelined `GET`, and the `EVAL` scripts behind counters and
 * hashes) on a scratch key of its own that expires in a minute, plus the response envelope's JSON. In the `wrm`
 * scope, which no module uses.
 */
class InfrastructureWarmUp(private val redis: RedisScope) : WarmUp {
    override val name = "infrastructure"

    override suspend fun run() {
        val key = "scratch:${UUID.randomUUID()}"
        redis.set(key, "1", TTL)
        redis.get(key)
        redis.exists(key)
        redis.getPipelined(listOf(key, "$key:missing"))
        redis.increment("$key:count", TTL)
        redis.hashSet("$key:hash", mapOf("a" to "1"), TTL)
        redis.hashIncrement("$key:hash", "a")
        redis.hashTake("$key:hash")
        redis.delete(key)
        redis.delete("$key:count")
        CircasoJson.encodeToString(
            DefaultResponse.serializer(String.serializer()),
            DefaultResponse(status = true, data = key),
        )
    }

    companion object {
        const val REDIS_PREFIX = "wrm"
        private val TTL: Duration = Duration.ofMinutes(1)
    }
}
