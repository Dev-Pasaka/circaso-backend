package com.circaso.messaging.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.messaging.domain.repositories.MessagingOutbox
import com.circaso.shared.EventEnvelope

/**
 * Messaging's outbox rows, through infrastructure's `writeOutbox` (decision 2026-10-27, PG1 review): inside the
 * use case's transaction when one is open, so the event lands with the change that caused it or not at all.
 */
class MessagingOutboxImpl(private val database: Database) : MessagingOutbox {
    override suspend fun write(events: List<EventEnvelope>) {
        if (events.isEmpty()) return
        database.tx("outbox.write") { events.forEach { database.writeOutbox(it) } }
    }
}
