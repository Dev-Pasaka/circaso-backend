package com.circaso.app

import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.RedisScope
import com.circaso.infrastructure.api.RedisScopes
import com.circaso.infrastructure.api.WarmUp
import com.circaso.shared.CircasoJson
import com.circaso.web.DefaultResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.serializer
import net.logstash.logback.argument.StructuredArguments.kv
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import java.time.Duration
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
 * How long and how hard the warm-up runs. `WARMUP_ENABLED` (default true), `WARMUP_WORKERS` (how many run at once,
 * default the app pool's size, so every pooled connection is used and warmed), `WARMUP_ROUNDS` (passes per worker,
 * default 100) and `WARMUP_BUDGET_MS` (at most this long in all, default 15 s; past it the process is ready anyway).
 */
data class WarmUpSettings(
    val enabled: Boolean = true,
    val workers: Int = 10,
    val rounds: Int = 100,
    val budget: Duration = Duration.ofSeconds(15),
) {
    companion object {
        fun from(config: Config): WarmUpSettings {
            val section = config.section("warmup")
            // The same setting (and default) as infrastructure's app pool: one worker per pooled connection.
            val poolSize = config.section("database").section("pool").int("app-size", 10)
            return WarmUpSettings(
                enabled = section.boolean("enabled", true),
                workers = section.int("workers", poolSize).coerceIn(1, 64),
                rounds = section.int("rounds", 100).coerceAtLeast(1),
                budget = Duration.ofMillis(section.int("budget-ms", 15_000).coerceAtLeast(0).toLong()),
            )
        }
    }
}

/** What a warm-up did: how many passes ran, whether it ran out of time, and which warm-ups failed (with the exception class). */
data class WarmUpOutcome(val passes: Int, val timedOut: Boolean, val failed: Map<String, String>)

/**
 * Runs every [WarmUp] [WarmUpSettings.rounds] times on each of [WarmUpSettings.workers] coroutines at once, within
 * [WarmUpSettings.budget]. A warm-up that throws is logged once (its exception's class, never its message, which can
 * carry a value) and not called again: a warm-up is never worth a retry loop against a dependency that is down.
 */
class WarmUpRunner(private val warmUps: List<WarmUp>, private val settings: WarmUpSettings) {
    suspend fun run(): WarmUpOutcome {
        if (!settings.enabled || warmUps.isEmpty()) return WarmUpOutcome(0, timedOut = false, failed = emptyMap())
        val started = System.nanoTime()
        val passes = AtomicInteger()
        val failed = ConcurrentHashMap<String, String>()
        val finished =
            withTimeoutOrNull(settings.budget.toMillis().milliseconds) {
                coroutineScope {
                    repeat(settings.workers) {
                        launch {
                            repeat(settings.rounds) {
                                for (warmUp in warmUps) {
                                    if (failed.containsKey(warmUp.name)) continue
                                    try {
                                        warmUp.run()
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        if (failed.putIfAbsent(warmUp.name, e::class.simpleName ?: "Exception") ==
                                            null
                                        ) {
                                            log.warn(
                                                "Warm-up '${warmUp.name}' failed (${e::class.simpleName}); skipping it",
                                            )
                                        }
                                    }
                                }
                                passes.incrementAndGet()
                            }
                        }
                    }
                }
            } != null
        val outcome = WarmUpOutcome(passes.get(), timedOut = !finished, failed = failed.toMap())
        val fields =
            arrayOf(
                kv("durationMs", (System.nanoTime() - started) / 1_000_000),
                kv("passes", outcome.passes),
                kv("workers", settings.workers),
                kv("warmUps", warmUps.map { it.name }),
                kv("failed", outcome.failed.keys.sorted()),
            )
        if (outcome.timedOut) {
            log.warn("Warm-up ran out of time; ready anyway", *fields)
        } else {
            log.info("Warm-up done", *fields)
        }
        return outcome
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
