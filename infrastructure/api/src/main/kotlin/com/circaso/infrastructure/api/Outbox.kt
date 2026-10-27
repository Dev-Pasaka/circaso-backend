package com.circaso.infrastructure.api

import com.circaso.shared.EventEnvelope
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Implemented by each module's `data` layer over its own outbox table, so the relay can rescue events without knowing
 * any module's tables. A row is written in the same transaction as the change that caused it.
 */
interface OutboxStore {
    /** The module that owns this outbox; its stream is `events:<producer>`. */
    val producer: String

    /**
     * Up to [limit] rows with no `publishedAt` that occurred more than 10 seconds ago (the normal publish path owns the
     * newer ones), oldest first. On PostgreSQL: `WHERE published_at IS NULL AND occurred_at < now() - interval
     * '10 seconds' ORDER BY occurred_at LIMIT :limit`, served by the partial index `WHERE published_at IS NULL`.
     */
    suspend fun unpublished(limit: Int = BATCH): List<EventEnvelope>

    /**
     * [unpublished], saying whether the read filled its window of [limit] rows. A store that skips rows it can't
     * decode returns fewer events than it read, and the relay must not take that for the last batch.
     */
    suspend fun page(limit: Int = BATCH): OutboxPage {
        val events = unpublished(limit)
        return OutboxPage(events, full = events.size >= limit)
    }

    /** Stamps `publishedAt` once the event is on the stream. */
    suspend fun markPublished(envelope: EventEnvelope, at: Instant)

    companion object {
        /** How many rows the relay asks for at a time. */
        const val BATCH = 500
    }
}

/** One read of an outbox: the [events] to relay, and whether the read came back with as many rows as it asked for. */
data class OutboxPage(val events: List<EventEnvelope>, val full: Boolean)

/**
 * How a module still on ScyllaDB answers [OutboxStore.unpublished]: its outbox is keyed by minute-buckets
 * ([EventEnvelope.bucket]), so this walks the buckets that ended more than [margin] ago, from the first one that still
 * held an unpublished row the last time (or [firstLookback] back on this node's first call), at most [maxBuckets] a
 * call. [read] returns a bucket's unpublished rows.
 *
 * Where to start is kept in memory and never more than [firstLookback] back: a node that takes over the relay starts
 * there, and a row older than that is no longer rescued by this scan.
 * Temporary: PG2–PG5 replace each Kandra outbox with a PostgreSQL one, and PG6 deletes this.
 */
class BucketedOutboxScan(
    private val clock: Clock = Clock.systemUTC(),
    private val margin: Duration = Duration.ofSeconds(10),
    private val firstLookback: Duration = Duration.ofHours(1),
    private val maxBuckets: Int = 120,
    private val read: suspend (bucket: String) -> List<EventEnvelope>,
) {
    @Volatile
    private var from: String? = null

    suspend fun unpublished(limit: Int): List<EventEnvelope> {
        val now = clock.instant()
        val oldest = EventEnvelope.bucketOf(now.minus(firstLookback))
        // Never further back than the lookback: a row whose stamp keeps failing must not pin the start for ever, or
        // the scan, capped at maxBuckets, would stop reaching newer rows.
        var bucket = from?.takeIf { it > oldest } ?: oldest
        var firstWithRows: String? = null
        val found = mutableListOf<EventEnvelope>()
        var buckets = 0
        while (buckets < maxBuckets && found.size < limit && ended(bucket, now)) {
            val rows = read(bucket)
            if (rows.isNotEmpty() && firstWithRows == null) firstWithRows = bucket
            found += rows
            bucket = EventEnvelope.nextBucket(bucket)
            buckets++
        }
        // Rows found are published and stamped before the next call, which then moves on past their bucket.
        from = firstWithRows ?: bucket
        return found.take(limit)
    }

    private fun ended(bucket: String, now: Instant): Boolean =
        !EventEnvelope.bucketStart(bucket).plusSeconds(60).isAfter(now.minus(margin))
}
