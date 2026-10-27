package com.circaso.identity.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.shared.EventEnvelope

// Inside tx { }, `db` is the transaction's own (Exposed's), so this writeOutbox never reaches the module's handle.
class NotesRepositoryImpl(private val db: Database) {
    suspend fun add(event: EventEnvelope) = db.tx("notes.add") { db.writeOutbox(event) }
}

class OtherRepositoryImpl(
    private val store: Database,
) {
    fun schema(): Database = store // a return type is not a declaration
}

// Allowed: the handle is called database.
class GoodRepositoryImpl(private val database: Database) {
    suspend fun add(event: EventEnvelope) = database.tx("notes.add") { database.writeOutbox(event) }
}
