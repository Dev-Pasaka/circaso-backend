package com.circaso.users.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.shared.Resource
import com.circaso.users.domain.ports.Transactor

/** The domain's transaction boundary over users' [Database]: repositories called inside it join the transaction. */
class DatabaseTransactor(private val database: Database) : Transactor {
    override suspend fun <T> run(operation: String, block: suspend () -> T): T = database.tx(operation) { block() }

    override suspend fun <T> attempt(operation: String, block: suspend () -> Resource<T>): Resource<T> =
        try {
            database.tx(operation) {
                block().also { if (it is Resource.Error) throw RolledBack(it) }
            }
        } catch (e: RolledBack) {
            e.error
        }
}

/** Carries a block's error out of `Database.tx`, which rolls back on any exception; never leaves this file. */
private class RolledBack(val error: Resource.Error) : RuntimeException(null, null, false, false)
