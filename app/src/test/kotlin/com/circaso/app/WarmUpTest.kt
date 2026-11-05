package com.circaso.app

import com.circaso.infrastructure.api.HealthCheck
import com.circaso.infrastructure.api.WarmUp
import com.circaso.infrastructure.impl.config.EnvConfig
import com.circaso.shared.Resource
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WarmUpTest {
    /** Counts its runs and how many ran at once at most. */
    private class Counting(override val name: String, private val pause: Long = 5) : WarmUp {
        val runs = AtomicInteger()
        private val running = AtomicInteger()
        val mostAtOnce = AtomicInteger()

        override suspend fun run() {
            mostAtOnce.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            delay(pause)
            running.decrementAndGet()
            runs.incrementAndGet()
        }
    }

    private val up =
        object : HealthCheck {
            override val name = "postgres"

            override suspend fun check() = Resource.success(Unit)
        }

    @Test
    fun `every warm-up runs rounds times on each worker, the workers at once`() =
        runBlocking {
            val a = Counting("a")
            val b = Counting("b")

            val outcome = WarmUpRunner(listOf(a, b), WarmUpSettings(workers = 4, rounds = 3)).run()

            assertEquals(WarmUpOutcome(passes = 12, timedOut = false, failed = emptyMap()), outcome)
            assertEquals(12, a.runs.get())
            assertEquals(12, b.runs.get())
            assertEquals(4, a.mostAtOnce.get(), "one run per worker at a time, every worker at once")
        }

    @Test
    fun `a warm-up that throws is reported once and not called again, and the others carry on`() =
        runBlocking {
            val calls = AtomicInteger()
            val broken =
                object : WarmUp {
                    override val name = "broken"

                    override suspend fun run() {
                        calls.incrementAndGet()
                        throw IllegalStateException("database down")
                    }
                }
            val fine = Counting("fine", pause = 0)

            val outcome = WarmUpRunner(listOf(broken, fine), WarmUpSettings(workers = 1, rounds = 5)).run()

            assertEquals(mapOf("broken" to "IllegalStateException"), outcome.failed)
            assertEquals(1, calls.get())
            assertEquals(5, fine.runs.get())
            assertFalse(outcome.timedOut)
        }

    @Test
    fun `a warm-up that runs out of its budget stops there and says so`() =
        runBlocking {
            val slow = Counting("slow", pause = 10_000)
            val started = System.nanoTime()

            val outcome =
                WarmUpRunner(listOf(slow), WarmUpSettings(workers = 2, rounds = 10, budget = Duration.ofMillis(100)))
                    .run()

            assertTrue(outcome.timedOut)
            assertEquals(0, outcome.passes)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000, "stopped at the budget, not after the runs")
        }

    @Test
    fun `turned off, or with nothing to warm, it returns at once`() =
        runBlocking {
            val a = Counting("a")
            assertEquals(0, WarmUpRunner(listOf(a), WarmUpSettings(enabled = false)).run().passes)
            assertEquals(0, a.runs.get())
            assertEquals(0, WarmUpRunner(emptyList(), WarmUpSettings()).run().passes)
        }

    @Test
    fun `settings come from WARMUP_ and default to one worker per pooled app connection`() {
        val defaults = WarmUpSettings.from(EnvConfig(emptyMap()))
        assertEquals(
            WarmUpSettings(enabled = true, workers = 10, rounds = 100, budget = Duration.ofSeconds(15)),
            defaults,
        )

        val set =
            WarmUpSettings.from(
                EnvConfig(
                    mapOf(
                        "DATABASE_POOL_APP_SIZE" to "6",
                        "WARMUP_ROUNDS" to "7",
                        "WARMUP_BUDGET_MS" to "2500",
                        "WARMUP_ENABLED" to "false",
                    ),
                ),
            )
        assertEquals(WarmUpSettings(enabled = false, workers = 6, rounds = 7, budget = Duration.ofMillis(2500)), set)
    }

    @Test
    fun `ready is 503 with warm-up down until the warm-up has finished, then 200, and live is 200 throughout`() =
        testApplication {
            val readiness = Readiness()
            application { circasoModule(AppConfig(), listOf(up), readiness = readiness) }

            val before = client.get("/health/ready")
            assertEquals(HttpStatusCode.ServiceUnavailable, before.status)
            assertEquals(
                HealthReport(
                    "not_ready",
                    listOf(HealthCheckStatus("postgres", "up"), HealthCheckStatus(WARM_UP_CHECK, "down")),
                ),
                Json.decodeFromString<HealthReport>(before.bodyAsText()),
            )
            assertEquals(HttpStatusCode.OK, client.get("/health/live").status)

            readiness.markReady()

            val after = client.get("/health/ready")
            assertEquals(HttpStatusCode.OK, after.status)
            assertEquals(
                HealthReport(
                    "ready",
                    listOf(HealthCheckStatus("postgres", "up"), HealthCheckStatus(WARM_UP_CHECK, "up")),
                ),
                Json.decodeFromString<HealthReport>(after.bodyAsText()),
            )
        }
}
