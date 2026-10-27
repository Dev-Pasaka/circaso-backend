package com.circaso.identity.domain.ports

import com.circaso.shared.Resource

/**
 * One operation, one transaction (architecture.md → Transactions). Repositories called inside it join it; outside
 * it, each repository call opens its own. The block holds repository calls only: Redis, HTTP and event publishing
 * happen before or after it.
 */
interface Transactor {
    /** Runs [block] in one transaction; [operation] is a string literal naming it in the log line. */
    suspend fun <T> run(operation: String, block: suspend () -> T): T

    /**
     * Like [run], for an operation that can be refused part-way: when [block] returns a `Resource.Error`, everything it
     * wrote is rolled back and the error is returned; a `Resource.Success` commits. A lost claim (a repository method
     * answering false) is answered this way, so no write before it survives.
     */
    suspend fun <T> attempt(operation: String, block: suspend () -> Resource<T>): Resource<T>
}
