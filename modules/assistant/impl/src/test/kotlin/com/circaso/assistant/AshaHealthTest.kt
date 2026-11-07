package com.circaso.assistant

import com.circaso.assistant.data.cache.RedisAshaHealth
import com.circaso.assistant.domain.models.ProbeState
import com.circaso.assistant.domain.models.TurnCommand
import com.circaso.assistant.domain.support.HealthSchedule
import com.circaso.assistant.domain.usecases.GetAshaStatus
import com.circaso.assistant.domain.usecases.MonitorAshaHealth
import com.circaso.assistant.presentation.controllers.statusController
import com.circaso.assistant.presentation.services.StatusService
import com.circaso.infrastructure.api.RedisScope
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.ModelStreamEvent
import com.circaso.shared.DeviceId
import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.UserId
import com.circaso.web.CallPrincipal
import com.circaso.web.CallPrincipalKey
import com.circaso.web.installJson
import com.circaso.web.installStatusPages
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Asha down for maintenance (decision 2026-11-07, Claude only, no fallback model): how a turn's failure becomes
 * `assistant.unavailable`, who is recorded, the health monitor's probing, the one "she's back" notice each, and A14.
 */
class AshaHealthTest {
    private val start = Instant.parse("2026-11-07T12:00:00Z")

    /** A clock the test moves. */
    private class Ticking(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?) = this

        override fun instant() = now
    }

    private fun ask(h: Harness, text: String = "hello") = TurnCommand(h.owner, text, null, true, "agent-tok", "req-1")

    private fun Resource<*>.errorCode() = (this as Resource.Error).error.code

    // ---- turns -------------------------------------------------------------------------------------------------

    @Test
    fun `a model that can't answer before its first word marks Asha down, and the turn says maintenance`() =
        runTest {
            val h = Harness()
            h.model.fail()

            assertEquals("assistant.unavailable", h.start(ask(h)).errorCode())
            assertFalse(h.health.status().isAvailable)
            assertEquals(setOf(h.owner), h.health.affected)
            assertTrue(h.memory.messages.isEmpty(), "nothing saved: the app keeps the question")
        }

    @Test
    fun `each person is recorded once however often they try during the outage`() =
        runTest {
            val h = Harness()
            repeat(3) { h.model.fail() }

            repeat(3) { assertEquals("assistant.unavailable", h.start(ask(h)).errorCode()) }

            assertEquals(setOf(h.owner), h.health.affected)
            assertEquals(listOf(h.owner), h.health.takeAffected())
        }

    @Test
    fun `a refusal is not an outage, and neither is a failure after the first words`() =
        runTest {
            val h = Harness()
            h.model.reject()
            assertEquals("assistant.model_unavailable", h.start(ask(h)).errorCode())

            h.model.streamed("Let me", " check", end = ModelStreamEvent.Failed(IntegrationError.Unavailable("model")))
            assertEquals("assistant.model_unavailable", h.start(ask(h)).errorCode())

            assertTrue(h.health.status().isAvailable)
            assertTrue(h.health.affected.isEmpty())
        }

    @Test
    fun `any real answer while she is down marks her up`() =
        runTest {
            val h = Harness()
            h.model.fail()
            h.start(ask(h))
            assertFalse(h.health.status().isAvailable)

            h.model.say("Hi again.")
            assertTrue(h.start(ask(h)) is Resource.Success)

            assertTrue(h.health.status().isAvailable)
        }

    // ---- the monitor -------------------------------------------------------------------------------------------

    private class Monitor(val h: Harness, val clock: Ticking) {
        val frames = RecordingStatusFrames()
        val tick = MonitorAshaHealth(h.health, h.model, frames, clock)

        suspend fun down(at: Instant = clock.now) = h.health.markDown(at)

        suspend fun at(seconds: Long): Int {
            clock.now = clock.now.plusSeconds(seconds)
            return (tick() as Resource.Success).data
        }
    }

    private fun monitor(): Monitor {
        val h = Harness()
        val clock = Ticking(start)
        h.health.now = { clock.now }
        return Monitor(h, clock)
    }

    private val down: Resource<Unit> = Resource.error(IntegrationError.Unavailable("Anthropic"))

    @Test
    fun `the probe backs off from 30 s to 2 min, capped, and stops at the first answer`() =
        runTest {
            val m = monitor()
            m.down()
            repeat(5) { m.h.model.probes += down }

            assertEquals(0, m.at(29), "the first probe is 30 s after she went down")
            assertEquals(0, m.h.model.probed)
            m.at(1)
            assertEquals(1, m.h.model.probed)
            assertEquals(ProbeState(1, start.plusSeconds(60)), m.h.health.probe())
            m.at(29)
            assertEquals(1, m.h.model.probed, "30 s after the first")
            m.at(1)
            assertEquals(2, m.h.model.probed)
            assertEquals(
                start.plusSeconds(120),
                m.h.health
                    .probe()!!
                    .nextAt,
                "then 1 min",
            )
            m.at(60)
            assertEquals(
                start.plusSeconds(240),
                m.h.health
                    .probe()!!
                    .nextAt,
                "then 2 min",
            )
            m.at(120)
            assertEquals(
                start.plusSeconds(360),
                m.h.health
                    .probe()!!
                    .nextAt,
                "and 2 min from then on",
            )
            m.at(120)
            assertEquals(5, m.h.model.probed)

            m.at(120) // the sixth probe answers
            assertEquals(6, m.h.model.probed)
            assertTrue(
                m.h.health
                    .status()
                    .isAvailable,
            )
            assertNull(m.h.health.probe(), "a new outage starts its probing afresh")
            m.at(120)
            assertEquals(6, m.h.model.probed, "no probing while she is up")
        }

    @Test
    fun `the schedule is 30 s, 30 s, 1 min, 2 min, then 2 min`() {
        assertEquals(
            listOf(30L, 30L, 60L, 120L, 120L, 120L),
            (0..5).map { HealthSchedule.backoff(it).seconds },
        )
    }

    @Test
    fun `on recovery everyone who met her down is told once, and the list is emptied`() =
        runTest {
            val m = monitor()
            val others = List(2) { UserId.new() }
            m.down()
            (others + others).forEach { m.h.health.recordAffected(it) }

            m.at(30) // the probe answers
            val (told, status) = m.frames.sent.single()

            assertEquals(others.toSet(), told.toSet())
            assertEquals(2, told.size, "once each")
            assertTrue(status.isAvailable)
            assertEquals(start.plusSeconds(30), status.since)
            assertTrue(
                m.h.health.affected
                    .isEmpty(),
            )
            m.at(10)
            assertEquals(1, m.frames.sent.size, "nobody is told twice")
        }

    @Test
    fun `a real answer bringing her back is announced on the next tick`() =
        runTest {
            val m = monitor()
            m.h.model.fail()
            m.h.start(ask(m.h))
            m.h.model.say("Back.")
            m.h.start(ask(m.h))

            m.at(10)

            assertEquals(
                listOf(m.h.owner),
                m.frames.sent
                    .single()
                    .first,
            )
            assertEquals(0, m.h.model.probed, "she was already up: no probe")
        }

    @Test
    fun `a flapping outage tells each person at most once in six hours`() =
        runTest {
            val m = monitor()
            val person = UserId.new()
            repeat(3) {
                m.down()
                m.h.health.recordAffected(person)
                m.at(30)
                m.at(60)
            }
            assertEquals(1, m.frames.sent.size)

            m.clock.now = m.clock.now.plus(Duration.ofHours(6))
            m.down()
            m.h.health.recordAffected(person)
            m.at(30)
            assertEquals(2, m.frames.sent.size, "a new outage after the window is told again")
        }

    @Test
    fun `nobody recorded, nothing sent`() =
        runTest {
            val m = monitor()
            m.down()
            assertEquals(1, m.at(30), "one probe")
            assertTrue(m.frames.sent.isEmpty())
        }

    // ---- A14 ---------------------------------------------------------------------------------------------------

    @Test
    fun `A14 says whether Asha can answer now, and since when`() =
        testApplication {
            val h = Harness()
            val service =
                StatusService(
                    GetAshaStatus(h.health),
                    MonitorAshaHealth(h.health, h.model, RecordingStatusFrames(), Clock.systemUTC()),
                )
            application {
                installJson()
                installStatusPages()
                intercept(ApplicationCallPipeline.Plugins) {
                    call.attributes.put(
                        CallPrincipalKey,
                        CallPrincipal(h.owner, DeviceId.new(), UUID.randomUUID(), Scope.APP),
                    )
                }
                routing { route("/v1") { statusController(service) } }
            }

            suspend fun status() =
                client.get("/v1/assistant/status").let {
                    assertEquals(HttpStatusCode.OK, it.status)
                    Json.parseToJsonElement(it.bodyAsText()).jsonObject["data"]!!.jsonObject
                }

            val never = status()
            assertTrue(never["available"]!!.jsonPrimitive.boolean)
            assertEquals(JsonNull, never["since"])

            h.health.markDown(start)
            val down = status()
            assertFalse(down["available"]!!.jsonPrimitive.boolean)
            assertEquals("2026-11-07T12:00:00Z", down["since"]!!.jsonPrimitive.content)

            h.health.markUp(start.plusSeconds(90))
            val back = status()
            assertTrue(back["available"]!!.jsonPrimitive.boolean)
            assertEquals("2026-11-07T12:01:30Z", back["since"]!!.jsonPrimitive.content)
        }

    // ---- the Redis store ---------------------------------------------------------------------------------------

    /** Plain values and hashes in maps, expiry not modelled. */
    private class MapRedis : RedisScope {
        override val prefix = "ast"
        val values = ConcurrentHashMap<String, String>()
        val hashes = ConcurrentHashMap<String, MutableMap<String, String>>()
        val ttls = ConcurrentHashMap<String, Duration>()

        override suspend fun get(key: String) = values[key]

        override suspend fun getPipelined(keys: List<String>) = keys.map { values[it] }

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
        ) = (values.putIfAbsent(key, value) == null).also { if (it) ttls[key] = ttl }

        override suspend fun getAndDelete(key: String) = values.remove(key)

        override suspend fun delete(key: String) = (values.remove(key) ?: hashes.remove(key)) != null

        override suspend fun exists(key: String) = values.containsKey(key) || hashes.containsKey(key)

        override suspend fun increment(key: String, ttl: Duration) = error("not used")

        override suspend fun incrementBy(
            key: String,
            by: Long,
            ttl: Duration,
        ) = error("not used")

        override suspend fun incrementRenewing(key: String, ttl: Duration) = error("not used")

        override suspend fun hashSet(
            key: String,
            fields: Map<String, String>,
            ttl: Duration,
        ) {
            hashes.getOrPut(key) { ConcurrentHashMap() }.putAll(fields)
            ttls[key] = ttl
        }

        override suspend fun hashGetAll(key: String): Map<String, String> = hashes[key].orEmpty()

        override suspend fun hashTake(key: String): Map<String, String> = hashes.remove(key).orEmpty()

        override suspend fun hashDelete(key: String, field: String) = error("not used")

        override suspend fun hashIncrement(
            key: String,
            field: String,
            by: Long,
        ) = error("not used")

        override suspend fun ttl(key: String) = error("not used")

        override suspend fun publish(channel: String, message: String) = error("not used")

        override fun subscribe(channel: String): Flow<String> = emptyFlow()
    }

    @Test
    fun `the Redis store keeps ids only, one winner per change, and every key expires`() =
        runTest {
            val redis = MapRedis()
            val health = RedisAshaHealth(redis)
            val (a, b) = List(2) { UserId.new() }

            assertTrue(health.status().isAvailable)
            assertTrue(health.markDown(start))
            assertFalse(health.markDown(start.plusSeconds(5)), "already down: the first time stands")
            assertEquals(start, health.status().since)
            health.saveProbe(ProbeState(2, start.plusSeconds(90)))
            assertEquals(ProbeState(2, start.plusSeconds(90)), health.probe())
            health.recordAffected(a)
            health.recordAffected(b)
            health.recordAffected(a)
            assertEquals<Map<String, String>?>(
                mapOf(a.toString() to "1", b.toString() to "1"),
                redis.hashes["health:affected"],
            )

            assertTrue(health.markUp(start.plusSeconds(60)))
            assertFalse(health.markUp(start.plusSeconds(61)), "exactly one node marks her up")
            assertEquals(start.plusSeconds(60), health.status().since)
            assertNull(health.probe())
            assertEquals(setOf(a, b), health.takeAffected().toSet())
            assertTrue(health.takeAffected().isEmpty())

            assertTrue(health.claimNotice(a, Duration.ofHours(6)))
            assertFalse(health.claimNotice(a, Duration.ofHours(6)))
            assertEquals(Duration.ofHours(6), redis.ttls["health:notice:$a"])
            assertEquals(Duration.ofHours(24), redis.ttls["health:affected"])
            assertTrue(redis.ttls.values.all { it > Duration.ZERO })
        }
}
