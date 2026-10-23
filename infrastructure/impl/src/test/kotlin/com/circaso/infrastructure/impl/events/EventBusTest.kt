package com.circaso.infrastructure.impl.events

import com.circaso.infrastructure.api.OutboxStore
import com.circaso.infrastructure.impl.TestRedis
import com.circaso.shared.DomainEvent
import com.circaso.shared.EventEnvelope
import com.circaso.shared.EventHandler
import com.circaso.shared.EventSubscription
import com.circaso.shared.decode
import com.circaso.shared.envelope
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.Range
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Serializable
private data class Pinged(val producerName: String, val n: Int) : DomainEvent {
    override val type: String get() = "$producerName.Pinged"
}

@Serializable
private data class Ponged(val producerName: String) : DomainEvent {
    override val type: String get() = "$producerName.Ponged"
}

@OptIn(ExperimentalLettuceCoroutinesApi::class)
class EventBusTest {

    private val redis = TestRedis.connect()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val suffix = UUID.randomUUID().toString().replace("-", "").take(8).map { 'a' + (it.digitToIntOrNull(16) ?: 0) }.joinToString("")
    private val producer = "p$suffix"
    private val group = "g$suffix"
    private val stream = "events:$producer"

    // Short waits, the same behaviour: the architecture's 30 s reclaim is 300 ms here.
    private val fast = EventRuntimeConfig(claimIdle = Duration.ofMillis(300), readBlock = Duration.ofMillis(100), metricsInterval = Duration.ofMillis(200))

    private val buses = mutableListOf<RedisEventBus>()

    private fun bus(stores: List<OutboxStore> = emptyList(), clock: Clock = Clock.systemUTC(), config: EventRuntimeConfig = fast) =
        RedisEventBus(redis, { stores }, config = config, clock = clock).also { buses += it }

    @AfterTest
    fun close() {
        buses.forEach { it.close() }
        scope.cancel()
        redis.close()
    }

    private suspend fun eventually(timeoutMillis: Long = 6000, check: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!check()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within ${timeoutMillis}ms" }
            delay(50)
        }
    }

    private fun subscription(vararg handlers: Pair<String, EventHandler>) = EventSubscription(group, stream, mapOf(*handlers))

    /** Pending messages of the group, or -1 while the consumer hasn't created the group yet. */
    private suspend fun pending(): Long = runCatching { redis.commands.xpending(stream, group)?.count ?: 0 }.getOrDefault(-1)

    @Test
    fun `a published event reaches the subscribed handler, is recorded as done and acknowledged`() = runBlocking {
        val received = ConcurrentLinkedQueue<Pinged>()
        val bus = bus()
        bus.subscribe(subscription("$producer.Pinged" to EventHandler { received += it.decode<Pinged>() }))
        bus.start(scope)

        val envelope = bus.envelope(Pinged(producer, 7))
        bus.publish(envelope)

        eventually { received.size == 1 }
        assertEquals(Pinged(producer, 7), received.single())
        eventually { redis.commands.exists("evt:done:$group:${envelope.eventId}") == 1L }
        eventually { pending() == 0L }
    }

    @Test
    fun `an event published before the group existed is still delivered`() = runBlocking {
        val bus = bus()
        bus.publish(bus.envelope(Pinged(producer, 1)))
        val received = ConcurrentLinkedQueue<Int>()

        bus.subscribe(subscription("$producer.Pinged" to EventHandler { received += it.decode<Pinged>().n }))
        bus.start(scope)

        eventually { received.toList() == listOf(1) }
    }

    @Test
    fun `the same event delivered twice is handled once`() = runBlocking {
        val handled = AtomicInteger()
        val bus = bus()
        bus.subscribe(subscription("$producer.Pinged" to EventHandler { handled.incrementAndGet() }))
        bus.start(scope)

        val envelope = bus.envelope(Pinged(producer, 1))
        bus.publish(envelope)
        eventually { handled.get() == 1 }
        bus.publish(envelope) // a retry or a relay rescue
        bus.publish(bus.envelope(Pinged(producer, 2)))
        eventually { handled.get() == 2 }
        delay(300)

        assertEquals(2, handled.get(), "the duplicate must be skipped, the new event handled")
    }

    @Test
    fun `an event type the group does not react to is acknowledged and ignored`() = runBlocking {
        val pings = AtomicInteger()
        val bus = bus()
        bus.subscribe(subscription("$producer.Pinged" to EventHandler { pings.incrementAndGet() }))
        bus.start(scope)

        bus.publish(bus.envelope(Ponged(producer)))
        bus.publish(bus.envelope(Pinged(producer, 1)))

        eventually { pings.get() == 1 }
        eventually { pending() == 0L }
    }

    @Test
    fun `a failing handler is retried after the idle time and then succeeds`() = runBlocking {
        val attempts = AtomicInteger()
        val bus = bus()
        bus.subscribe(subscription("$producer.Pinged" to EventHandler {
            if (attempts.incrementAndGet() < 3) error("not yet")
        }))
        bus.start(scope)

        bus.publish(bus.envelope(Pinged(producer, 1)))

        eventually(10_000) { attempts.get() == 3 }
        eventually { pending() == 0L }
        val deadForGroup = redis.commands.xrange(DEAD_LETTER_STREAM, Range.create("-", "+")).toList().count { it.body["group"] == group }
        assertEquals(0, deadForGroup, "an event that succeeded on a retry is not dead-lettered")
    }

    @Test
    fun `an event that keeps failing is dead-lettered after five attempts`() = runBlocking {
        val attempts = AtomicInteger()
        val bus = bus()
        bus.subscribe(subscription("$producer.Pinged" to EventHandler {
            attempts.incrementAndGet()
            error("always broken: secret detail")
        }))
        bus.start(scope)

        val envelope = bus.envelope(Pinged(producer, 1))
        bus.publish(envelope)

        eventually(15_000) { pending() == 0L && attempts.get() >= 5 }
        assertEquals(5, attempts.get())
        val dead = redis.commands.xrange(DEAD_LETTER_STREAM, Range.create("-", "+")).toList().single { it.body["group"] == group }
        assertEquals(stream, dead.body["stream"])
        assertEquals("5", dead.body["attempts"])
        assertEquals(envelope.eventId, EventEnvelope.fromJson(dead.body.getValue("e")).eventId)
        assertTrue(dead.body.getValue("error").startsWith("IllegalStateException"))
        assertFalse(redis.commands.exists("evt:done:$group:${envelope.eventId}") == 1L, "a failed event is never recorded as done")
    }

    @Test
    fun `two groups each get their own copy of an event`() = runBlocking {
        val first = AtomicInteger()
        val second = AtomicInteger()
        val bus = bus()
        bus.subscribe(EventSubscription("${group}a", stream, mapOf("$producer.Pinged" to EventHandler { first.incrementAndGet() })))
        bus.subscribe(EventSubscription("${group}b", stream, mapOf("$producer.Pinged" to EventHandler { second.incrementAndGet() })))
        bus.start(scope)

        bus.publish(bus.envelope(Pinged(producer, 1)))

        eventually { first.get() == 1 && second.get() == 1 }
    }

    @Test
    fun `publishing stamps the outbox row`() = runBlocking {
        val store = FakeOutbox(producer)
        val bus = bus(listOf(store))

        val envelope = bus.envelope(Pinged(producer, 1))
        store.rows += FakeOutbox.Row(envelope)
        bus.publish(envelope)

        assertNotNull(store.rows.single().publishedAt)
        assertEquals(1L, redis.commands.xlen(stream))
    }

    @Test
    fun `the relay publishes what a crashed node left unpublished, once, and moves its checkpoint`() = runBlocking {
        val store = FakeOutbox(producer)
        val bus = bus(listOf(store))
        val twoMinutesAgo = Clock.systemUTC().instant().minusSeconds(150)
        val stuck = EventEnvelope.of(Pinged(producer, 1), Pinged.serializer(), occurredAt = twoMinutesAgo)
        val alreadyOut = EventEnvelope.of(Pinged(producer, 2), Pinged.serializer(), occurredAt = twoMinutesAgo.plusSeconds(1))
        val tooFresh = bus.envelope(Pinged(producer, 3)) // inside the 10 s margin: the normal path still owns it
        store.rows += FakeOutbox.Row(stuck)
        store.rows += FakeOutbox.Row(alreadyOut, publishedAt = Instant.now())
        store.rows += FakeOutbox.Row(tooFresh)
        val relay = OutboxRelay(redis, bus, { listOf(store) })

        val first = relay.sweep()
        val second = relay.sweep()

        assertEquals(1, first)
        assertEquals(0, second, "the checkpoint moved past the swept buckets")
        assertEquals(1L, redis.commands.xlen(stream))
        assertNotNull(store.rows.first { it.envelope.eventId == stuck.eventId }.publishedAt)
        assertEquals(null, store.rows.first { it.envelope.eventId == tooFresh.eventId }.publishedAt)
        assertNotNull(redis.commands.get("evt:relay:$producer"))
    }

    @Test
    fun `a rescued event reaches a consumer exactly like a published one`() = runBlocking {
        val store = FakeOutbox(producer)
        val bus = bus(listOf(store))
        val received = ConcurrentLinkedQueue<Int>()
        bus.subscribe(subscription("$producer.Pinged" to EventHandler { received += it.decode<Pinged>().n }))
        bus.start(scope)
        store.rows += FakeOutbox.Row(EventEnvelope.of(Pinged(producer, 9), Pinged.serializer(), occurredAt = Instant.now().minusSeconds(120)))

        OutboxRelay(redis, bus, { listOf(store) }).sweep()

        eventually { received.toList() == listOf(9) }
    }

    @Test
    fun `consumers are healthy while their loops run and not before they start`() = runBlocking {
        val bus = bus()
        bus.subscribe(subscription("$producer.Pinged" to EventHandler { }))
        assertFalse(bus.consumersHealthy(), "a subscription with no running loop is not healthy")

        bus.start(scope)

        eventually { bus.consumersHealthy() }
    }

    /** An in-memory outbox, standing in for a module's outbox table. */
    private class FakeOutbox(override val producer: String) : OutboxStore {
        class Row(val envelope: EventEnvelope, @Volatile var publishedAt: Instant? = null)

        val rows = ConcurrentLinkedQueue<Row>()

        override suspend fun unpublished(bucket: String): List<EventEnvelope> =
            rows.filter { it.publishedAt == null && it.envelope.bucket == bucket }.map { it.envelope }

        override suspend fun markPublished(envelope: EventEnvelope, at: Instant) {
            rows.first { it.envelope.eventId == envelope.eventId }.publishedAt = at
        }
    }
}
