package com.circaso.infrastructure.impl.events

import com.circaso.infrastructure.api.HealthCheck
import com.circaso.infrastructure.api.OutboxStore
import com.circaso.infrastructure.impl.database.OnJobsPool
import com.circaso.infrastructure.impl.database.requireOutsideTransaction
import com.circaso.infrastructure.impl.logging.kv
import com.circaso.infrastructure.impl.logging.millisSince
import com.circaso.infrastructure.impl.redis.RedisConnection
import com.circaso.observability.api.Metrics
import com.circaso.observability.api.NoopTelemetry
import com.circaso.observability.api.Telemetry
import com.circaso.shared.DomainEvent
import com.circaso.shared.DomainException
import com.circaso.shared.EventBus
import com.circaso.shared.EventEnvelope
import com.circaso.shared.EventSubscription
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import io.lettuce.core.Consumer
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.RedisBusyException
import io.lettuce.core.SetArgs
import io.lettuce.core.StreamMessage
import io.lettuce.core.XAddArgs
import io.lettuce.core.XAutoClaimArgs
import io.lettuce.core.XGroupCreateArgs
import io.lettuce.core.XReadArgs
import io.lettuce.core.api.coroutines
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

private val log = LoggerFactory.getLogger("com.circaso.infrastructure.impl.events.EventBus")

const val DEAD_LETTER_STREAM = "events:dlq"

/** The architecture's numbers, in one place so tests can shrink the waits. */
data class EventRuntimeConfig(
    /** A message not acknowledged for this long is reclaimed and retried. */
    val claimIdle: Duration = Duration.ofSeconds(30),
    /**
     * How often each consumer looks for such messages (`XAUTOCLAIM`), and at once again while a look fills a whole
     * batch. A failed event is retried between [claimIdle] and [claimIdle] plus this after its failure. Looking on
     * every read (every [readBlock] when quiet) was one more Redis command per group every 2 s for nothing.
     */
    val claimEvery: Duration = Duration.ofSeconds(30),
    /** Handler attempts before an event moves to `events:dlq`. */
    val maxAttempts: Int = 5,
    /** Streams are trimmed to this age, and so are the done and attempt records. */
    val retention: Duration = Duration.ofDays(7),
    val readBlock: Duration = Duration.ofSeconds(2),
    val batchSize: Int = 10,
    val metricsInterval: Duration = Duration.ofSeconds(30),
    /**
     * How long a consumer waits after a handler found the database saturated (`service.unavailable`). That failure is
     * the pool's, not the event's: it is not counted as an attempt, so a burst never moves events toward the DLQ.
     */
    val busyBackoff: Duration = Duration.ofSeconds(5),
)

/**
 * Durable events over Redis Streams (Architecture → Durable events). `publish` is the fast path, a single `XADD`
 * right after the module's transaction committed; the [OutboxRelay] is the rescue path. Consumers read through one
 * consumer group per subscribing module, acknowledge only after their handler succeeds, are retried after
 * [EventRuntimeConfig.claimIdle], and dead-lettered after [EventRuntimeConfig.maxAttempts]. Each group records
 * handled event ids in `evt:done:{group}:{eventId}`, so a duplicate (a retry, a relay rescue) is skipped.
 * The `evt:` prefix belongs to this runtime, not to any module.
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class RedisEventBus(
    private val redis: RedisConnection,
    private val outboxStores: () -> Collection<OutboxStore> = { emptyList() },
    private val telemetry: Telemetry = NoopTelemetry,
    private val config: EventRuntimeConfig = EventRuntimeConfig(),
    private val clock: Clock = Clock.systemUTC(),
    private val nodeId: String = UUID.randomUUID().toString(),
) : EventBus,
    AutoCloseable {
    private class Consumption(val subscription: EventSubscription) {
        @Volatile var lastPollAt: Long = System.currentTimeMillis()

        @Volatile var job: Job? = null
        val lag = AtomicLong()
        val pending = AtomicLong()
    }

    private val consumptions = CopyOnWriteArrayList<Consumption>()
    private val dlqSize = AtomicLong()
    private val outboxRescued = ConcurrentHashMap<String, AtomicLong>()

    @Volatile
    private var scope: CoroutineScope? = null

    private val commands get() = redis.commands

    // ---- publishing ---------------------------------------------------------------------------------------

    override fun <E : DomainEvent> envelope(event: E, eventSerializer: KSerializer<E>): EventEnvelope =
        EventEnvelope.of(event, eventSerializer, occurredAt = clock.instant(), traceparent = telemetry.traceparent())

    override suspend fun publish(envelope: EventEnvelope) {
        requireOutsideTransaction("EventBus.publish") // publish right after the commit, never inside
        if (appendToStream(envelope)) stampOutbox(envelope)
    }

    /** `XADD` with the 7-day trim. False means it failed and the relay will publish it later; it never throws. */
    internal suspend fun appendToStream(envelope: EventEnvelope): Boolean =
        try {
            commands.xadd(
                envelope.stream,
                XAddArgs().minId(oldestKept()).approximateTrimming(),
                mapOf(ENVELOPE_FIELD to envelope.toJson()),
            )
            telemetry.count(Metrics.EVENTS_PUBLISHED, mapOf("stream" to envelope.stream, "type" to envelope.type))
            log.info(
                "published",
                kv("kind", "stream.publish"),
                kv("stream", envelope.stream),
                kv("eventType", envelope.type),
                kv("eventId", envelope.eventId),
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(
                "publish failed; the outbox relay will publish it",
                kv("kind", "stream.publish"),
                kv("stream", envelope.stream),
                kv("eventId", envelope.eventId),
                e,
            )
            false
        }

    /** Stamps `publishedAt` on the module's outbox row. A failure here only means the relay may publish it again. */
    internal suspend fun stampOutbox(envelope: EventEnvelope) {
        val store = outboxStores().firstOrNull { it.producer == envelope.producer } ?: return
        try {
            store.markPublished(envelope, clock.instant())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(
                "could not stamp the outbox row",
                kv("producer", envelope.producer),
                kv("eventId", envelope.eventId),
                e,
            )
        }
    }

    internal fun noteRescued(producer: String, count: Long) {
        outboxRescued
            .getOrPut(producer) {
                AtomicLong().also { holder ->
                    telemetry.gauge(
                        Metrics.OUTBOX_UNPUBLISHED,
                        mapOf("module" to producer),
                    ) { holder.get().toDouble() }
                }
            }.set(count)
    }

    private fun oldestKept(): String = "${clock.instant().minus(config.retention).toEpochMilli()}-0"

    // ---- consuming ----------------------------------------------------------------------------------------

    override fun subscribe(subscription: EventSubscription) {
        val consumption = Consumption(subscription)
        consumptions += consumption
        telemetry.gauge(Metrics.EVENTS_LAG, mapOf("group" to subscription.group)) {
            consumption.lag.get().toDouble() +
                consumption.pending.get()
        }
        scope?.let { launchConsumer(it, consumption) }
    }

    /** Starts every subscription, and any added later, plus the metrics refresh. */
    fun start(scope: CoroutineScope) {
        this.scope = scope
        telemetry.gauge(Metrics.EVENTS_DLQ, mapOf("stream" to DEAD_LETTER_STREAM)) { dlqSize.get().toDouble() }
        consumptions.forEach { launchConsumer(scope, it) }
        scope.launch {
            while (isActive) {
                refreshMetrics()
                delay(config.metricsInterval.toMillis())
            }
        }
    }

    override fun close() {
        consumptions.forEach { it.job?.cancel() }
        scope = null
    }

    private fun launchConsumer(scope: CoroutineScope, consumption: Consumption) {
        consumption.job = scope.launch { consume(consumption) }
    }

    private suspend fun consume(consumption: Consumption) {
        val subscription = consumption.subscription
        val connection = redis.openDedicated() // a blocking read must not hold the shared connection
        val reader = connection.coroutines()
        val consumer = Consumer.from(subscription.group, nodeId)
        val claims = ClaimSchedule(config.claimEvery, config.batchSize)
        try {
            ensureGroup(reader, subscription)
            while (currentCoroutineContext().isActive) {
                try {
                    if (claims.due(System.nanoTime())) {
                        val stale = claimStale(reader, consumer, subscription)
                        claims.ran(System.nanoTime(), stale.size)
                        stale.forEach { handle(reader, subscription, it) }
                    }
                    val fresh =
                        reader
                            .xreadgroup(
                                consumer,
                                XReadArgs.Builder.block(config.readBlock.toMillis()).count(config.batchSize.toLong()),
                                XReadArgs.StreamOffset.lastConsumed(subscription.stream),
                            ).toList()
                    fresh.forEach { handle(reader, subscription, it) }
                    consumption.lastPollAt = System.currentTimeMillis()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(
                        "consumer loop failed; retrying",
                        kv("kind", "stream.consume"),
                        kv("group", subscription.group),
                        kv("stream", subscription.stream),
                        e,
                    )
                    delay(1000)
                }
            }
        } finally {
            connection.close()
        }
    }

    private suspend fun ensureGroup(reader: RedisCoroutinesCommands<String, String>, subscription: EventSubscription) {
        try {
            // From the start of the stream, so a module that starts after the publisher still sees what is retained.
            reader.xgroupCreate(
                XReadArgs.StreamOffset.from(subscription.stream, "0"),
                subscription.group,
                XGroupCreateArgs.Builder.mkstream(),
            )
        } catch (e: RedisBusyException) {
            // the group exists already
        }
    }

    private suspend fun claimStale(
        reader: RedisCoroutinesCommands<String, String>,
        consumer: Consumer<String>,
        subscription: EventSubscription,
    ): List<StreamMessage<String, String>> =
        reader
            .xautoclaim(
                subscription.stream,
                XAutoClaimArgs.Builder.xautoclaim(consumer, config.claimIdle, "0-0").count(config.batchSize.toLong()),
            )?.messages
            .orEmpty()

    private suspend fun handle(
        reader: RedisCoroutinesCommands<String, String>,
        subscription: EventSubscription,
        message: StreamMessage<String, String>,
    ) {
        val stream = subscription.stream
        val raw = message.body[ENVELOPE_FIELD]
        val envelope = raw?.let { runCatching { EventEnvelope.fromJson(it) }.getOrNull() }
        if (envelope == null) {
            deadLetter(reader, subscription, message, raw, "unreadable envelope", attempts = 0)
            return
        }
        val doneKey = "evt:done:${subscription.group}:${envelope.eventId}"
        if (reader.exists(doneKey) == 1L) {
            reader.xack(stream, subscription.group, message.id)
            return
        }
        val handler = subscription.handlers[envelope.type]
        if (handler == null) {
            reader.xack(stream, subscription.group, message.id) // not an event this group reacts to
            return
        }

        val attemptKey = "evt:att:${subscription.group}:${envelope.eventId}"
        val attempt = redis.incrementWithTtl(attemptKey, config.retention).toInt()
        val start = System.nanoTime()
        val tags =
            arrayOf(
                kv("kind", "stream.consume"),
                kv("group", subscription.group),
                kv("eventType", envelope.type),
                kv("eventId", envelope.eventId),
                kv("attempt", attempt),
            )
        try {
            telemetry.span("event.${envelope.type}", mapOf("group" to subscription.group)) {
                withContext(OnJobsPool) { handler.handle(envelope) } // handlers are background work: the jobs pool
            }
            reader.set(doneKey, "1", SetArgs().px(config.retention.toMillis()))
            reader.xack(stream, subscription.group, message.id)
            telemetry.record(
                Metrics.EVENTS_HANDLED_DURATION,
                (System.nanoTime() - start) / 1e9,
                mapOf("group" to subscription.group, "type" to envelope.type, "outcome" to "ok"),
            )
            log.info("handled", *tags, kv("durationMs", 0L.millisSince(start)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.isServiceUnavailable()) {
                reader.decr(attemptKey) // the pool's failure, not the event's: not an attempt
                log.warn("database busy; will be retried", *tags, kv("backoffMs", config.busyBackoff.toMillis()))
                delay(config.busyBackoff.toMillis()) // left unacknowledged: reclaimed after the idle time
                return
            }
            telemetry.record(
                Metrics.EVENTS_HANDLED_DURATION,
                (System.nanoTime() - start) / 1e9,
                mapOf("group" to subscription.group, "type" to envelope.type, "outcome" to "error"),
            )
            if (attempt >= config.maxAttempts) {
                log.error("dead-lettered", *tags, e)
                deadLetter(
                    reader,
                    subscription,
                    message,
                    raw,
                    "${e::class.simpleName}: ${e.message?.take(200)}",
                    attempt,
                )
            } else {
                log.warn("failed; will be retried", *tags, e) // left unacknowledged: reclaimed after the idle time
            }
        }
    }

    private suspend fun deadLetter(
        reader: RedisCoroutinesCommands<String, String>,
        subscription: EventSubscription,
        message: StreamMessage<String, String>,
        raw: String?,
        error: String,
        attempts: Int,
    ) {
        reader.xadd(
            DEAD_LETTER_STREAM,
            XAddArgs().minId(oldestKept()).approximateTrimming(),
            mapOf(
                "stream" to subscription.stream,
                "group" to subscription.group,
                "id" to message.id,
                "e" to (raw ?: ""),
                "error" to error,
                "attempts" to attempts.toString(),
            ),
        )
        reader.xack(subscription.stream, subscription.group, message.id)
        dlqSize.incrementAndGet()
    }

    // ---- metrics and health ---------------------------------------------------------------------------------

    internal suspend fun refreshMetrics() {
        try {
            dlqSize.set(commands.xlen(DEAD_LETTER_STREAM) ?: 0)
            for (consumption in consumptions) {
                val group =
                    commands
                        .xinfoGroups(consumption.subscription.stream)
                        .orEmpty()
                        .map { entry -> (entry as List<*>).chunked(2).associate { (k, v) -> k.toString() to v } }
                        .firstOrNull { it["name"] == consumption.subscription.group } ?: continue
                consumption.pending.set((group["pending"] as? Number)?.toLong() ?: 0)
                consumption.lag.set((group["lag"] as? Number)?.toLong() ?: 0)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug("could not refresh event metrics", e)
        }
    }

    /** True when every consumer loop is alive and has polled recently. */
    internal fun consumersHealthy(maxSilence: Duration = Duration.ofSeconds(60)): Boolean =
        consumptions.all {
            it.job?.isActive == true && System.currentTimeMillis() - it.lastPollAt < maxSilence.toMillis()
        }

    private companion object {
        const val ENVELOPE_FIELD = "e"
    }
}

/**
 * When a consumer next looks for stale messages: at its start (a node that died left some), then every [every], and
 * straight away again after a look that filled a whole batch, since more may be waiting.
 */
internal class ClaimSchedule(private val every: Duration, private val batchSize: Int) {
    private var nextAt: Long? = null

    fun due(nowNanos: Long): Boolean = nextAt?.let { nowNanos - it >= 0 } ?: true

    fun ran(nowNanos: Long, claimed: Int) {
        nextAt = if (claimed >= batchSize) nowNanos else nowNanos + every.toNanos()
    }
}

class StreamConsumersHealth(private val bus: RedisEventBus) : HealthCheck {
    override val name = "streams"

    override suspend fun check(): Resource<Unit> =
        if (bus.consumersHealthy()) {
            Resource.success(
                Unit,
            )
        } else {
            Resource.error(SharedError.Internal)
        }
}

/** A handler that failed because no database connection came free in time (`service.unavailable`). */
private fun Throwable.isServiceUnavailable(): Boolean =
    generateSequence(this) { it.cause }
        .take(8)
        .any { it is DomainException && it.error == SharedError.ServiceUnavailable }
