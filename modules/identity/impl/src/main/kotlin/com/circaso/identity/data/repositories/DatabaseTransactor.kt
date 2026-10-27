package com.circaso.identity.data.repositories

import com.circaso.identity.domain.ports.Transactor
import com.circaso.infrastructure.api.Database
import com.circaso.shared.Resource

/** Identity's transactions, over its own schema and role; repositories called inside `run` join it. */
class DatabaseTransactor(private val database: Database) : Transactor {
    override suspend fun <T> run(operation: String, block: suspend () -> T): T = database.tx(operation) { block() }

    override suspend fun <T> attempt(operation: String, block: suspend () -> Resource<T>): Resource<T> =
        try {
            database.tx(operation) {
                when (val result = block()) {
                    is Resource.Success -> result

                    // Leaving the transaction by an exception is what rolls it back.
                    is Resource.Error -> throw Refused(result)
                }
            }
        } catch (refused: Refused) {
            refused.result
        }

    /** Carries a refusal out of the transaction; never leaves this class. */
    private class Refused(val result: Resource.Error) : RuntimeException(null, null, false, false)
}
