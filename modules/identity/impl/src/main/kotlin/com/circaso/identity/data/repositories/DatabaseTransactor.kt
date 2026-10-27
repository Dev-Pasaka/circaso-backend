package com.circaso.identity.data.repositories

import com.circaso.identity.domain.ports.Transactor
import com.circaso.infrastructure.api.Database

/** Identity's transactions, over its own schema and role; repositories called inside `run` join it. */
class DatabaseTransactor(private val database: Database) : Transactor {
    override suspend fun <T> run(operation: String, block: suspend () -> T): T = database.tx(operation) { block() }
}
