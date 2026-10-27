package com.circaso.users.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.users.domain.ports.Transactor

/** The domain's transaction boundary over users' [Database]: repositories called inside [run] join it. */
class DatabaseTransactor(private val database: Database) : Transactor {
    override suspend fun <T> run(operation: String, block: suspend () -> T): T = database.tx(operation) { block() }
}
