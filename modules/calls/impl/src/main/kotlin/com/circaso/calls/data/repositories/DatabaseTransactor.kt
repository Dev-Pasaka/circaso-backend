package com.circaso.calls.data.repositories

import com.circaso.calls.domain.ports.Transactor
import com.circaso.infrastructure.api.Database
import com.circaso.shared.Resource

/** [Transactor] over `Database.tx`; repositories called inside the block join its transaction. */
class DatabaseTransactor(private val database: Database) : Transactor {
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
