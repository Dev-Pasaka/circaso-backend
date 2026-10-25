package com.circaso.messaging.data.repositories

import com.circaso.infrastructure.api.OutboxStore
import com.circaso.messaging.data.entities.MessagingOutboxRow
import com.circaso.shared.EventEnvelope
import io.kandra.runtime.dsl.KandraColumnRef
import io.kandra.runtime.repository.KandraSuspendRepository
import java.time.Instant

// Column name Kandra derives from the entity property (camelCase to snake_case).
private val BUCKET = KandraColumnRef<String>("bucket")

/** Messaging's outbox for the relay, which rescues events whose immediate publish was lost. */
class MessagingOutboxStore(private val outbox: KandraSuspendRepository<MessagingOutboxRow>) : OutboxStore {
    override val producer = "messaging"

    override suspend fun unpublished(bucket: String): List<EventEnvelope> =
        outbox.findAll { BUCKET eq bucket }.filter { it.publishedAt == null }.map { EventEnvelope.fromJson(it.payload) }

    override suspend fun markPublished(envelope: EventEnvelope, at: Instant) {
        val row = outbox.findById(envelope.bucket, envelope.eventId.value) ?: return
        outbox.save(row.copy(publishedAt = at))
    }
}
