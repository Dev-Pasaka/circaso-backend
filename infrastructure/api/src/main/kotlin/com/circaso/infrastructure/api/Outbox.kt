package com.circaso.infrastructure.api

import com.circaso.shared.EventEnvelope
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
