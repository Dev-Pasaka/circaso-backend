package com.circaso.messaging.data.repositories

import com.circaso.infrastructure.api.BucketedOutboxScan
import com.circaso.infrastructure.api.OutboxStore
import com.circaso.messaging.data.entities.MessagingOutboxRow
import com.circaso.shared.EventEnvelope
import io.kandra.runtime.dsl.KandraColumnRef
import io.kandra.runtime.repository.KandraSuspendRepository
import java.time.Clock
import java.time.Instant

// Column name Kandra derives from the entity property (camelCase to snake_case).
private val BUCKET = KandraColumnRef<String>("bucket")

/** Messaging's outbox for the relay, which rescues events whose immediate publish was lost. */
class MessagingOutboxStore(
    private val outbox: KandraSuspendRepository<MessagingOutboxRow>,
    clock: Clock = Clock.systemUTC(),
) : OutboxStore {
    override val producer = "messaging"

    // The rows are keyed by minute-bucket; the bucket-less relay walks them through this until the outbox moves to
    // PostgreSQL.
    private val scan =
        BucketedOutboxScan(clock) { bucket ->
            outbox.findAll { BUCKET eq bucket }.filter { it.publishedAt == null }.map {
                EventEnvelope.fromJson(
                    it.payload,
                )
            }
        }

    override suspend fun unpublished(limit: Int): List<EventEnvelope> = scan.unpublished(limit)

    override suspend fun markPublished(envelope: EventEnvelope, at: Instant) {
        val row = outbox.findById(envelope.bucket, envelope.eventId.value) ?: return
        outbox.save(row.copy(publishedAt = at))
    }
}
