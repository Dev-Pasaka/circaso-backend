package com.circaso.wallet.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.shared.EventEnvelope
import com.circaso.wallet.domain.repositories.WalletOutbox

/**
 * Wallet's outbox rows, through infrastructure's `writeOutbox`: inside the use case's transaction when one is open, so
 * the event lands with the change that caused it or not at all.
 */
class WalletOutboxImpl(private val database: Database) : WalletOutbox {
    override suspend fun write(events: List<EventEnvelope>) {
        if (events.isEmpty()) return
        database.tx("outbox.write") { events.forEach { database.writeOutbox(it) } }
    }
}
