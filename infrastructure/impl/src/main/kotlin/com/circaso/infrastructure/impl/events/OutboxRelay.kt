package com.circaso.infrastructure.impl.events

import com.circaso.infrastructure.api.OutboxStore
import com.circaso.infrastructure.api.Scheduler
import com.circaso.infrastructure.impl.logging.kv
import com.circaso.infrastructure.impl.redis.RedisConnection
import com.circaso.shared.EventEnvelope
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.SetArgs
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration

private val log = LoggerFactory.getLogger("com.circaso.infrastructure.impl.events.OutboxRelay")

/**
 * The rescue path: publishes every outbox row that has no `publishedAt`, for the case where a node crashed
 * between its batch and its `XADD`. It runs under the scheduler's Redis lock, so one node at a time sweeps the
 * minute-buckets from its checkpoint up to 10 seconds ago, then moves the checkpoint forward. A rescued event
 * may be a duplicate of one that was published but not stamped; handlers skip those.
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class OutboxRelay(
    private val redis: RedisConnection,
    private val bus: RedisEventBus,
    private val outboxStores: () -> Collection<OutboxStore>,
    private val clock: Clock = Clock.systemUTC(),
    private val margin: Duration = Duration.ofSeconds(10),
    private val maxBucketsPerSweep: Int = 120,
    /** Where a store with no checkpoint yet starts: an hour back, never the whole 7 days. */
    private val firstLookback: Duration = Duration.ofHours(1),
    private val checkpointTtl: Duration = Duration.ofDays(8),
) {
    fun register(scheduler: Scheduler, interval: Duration = Duration.ofSeconds(5)) {
        scheduler.every("events.outbox-relay", interval) { sweep() }
    }

    /** One pass over every module's outbox. Returns how many events it published. */
    suspend fun sweep(): Int = outboxStores().sumOf { sweep(it) }

    private suspend fun sweep(store: OutboxStore): Int {
        val checkpointKey = "evt:relay:${store.producer}"
        val now = clock.instant()
        val swept = redis.commands.get(checkpointKey)
        var bucket = swept?.let(EventEnvelope::nextBucket) ?: EventEnvelope.bucketOf(now.minus(firstLookback))
        var published = 0
        var buckets = 0

        // A bucket is swept only once it ended more than [margin] ago, so the normal publish path has had its chance.
        while (buckets < maxBucketsPerSweep && !EventEnvelope.bucketStart(bucket).plusSeconds(60).isAfter(now.minus(margin))) {
            for (envelope in store.unpublished(bucket)) {
                if (!bus.appendToStream(envelope)) {
                    bus.noteRescued(store.producer, published.toLong())
                    return published // leave the checkpoint where it is and try again next sweep
                }
                bus.stampOutbox(envelope)
                published++
            }
            redis.commands.set(checkpointKey, bucket, SetArgs().px(checkpointTtl.toMillis()))
            bucket = EventEnvelope.nextBucket(bucket)
            buckets++
        }
        bus.noteRescued(store.producer, published.toLong())
        if (published > 0) log.warn("rescued unpublished events", kv("producer", store.producer), kv("events", published))
        return published
    }
}
