package com.circaso.identity.data.repositories

import com.circaso.identity.domain.ports.Transactor
import com.circaso.infrastructure.api.Database

class EmailRepositoryImpl(private val db: Database) {
    private val name = "emails.find"

    suspend fun find() = db.tx(name) { 1 }

    suspend fun count() = db.tx("emails.count") { 2 }
}

// Allowed: a Transactor hands its caller's literal on.
class EmailTransactor(private val db: Database) : Transactor {
    override suspend fun <T> run(operation: String, block: suspend () -> T): T = db.tx(operation) { block() }
}
