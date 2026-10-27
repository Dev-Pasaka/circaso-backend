package com.circaso.identity.domain.ports

/**
 * One operation, one transaction (architecture.md → Transactions). Repositories called inside [run] join it; outside
 * it, each repository call opens its own. The block holds repository calls only: Redis, HTTP and event publishing
 * happen before or after it.
 */
interface Transactor {
    /** Runs [block] in one transaction; [operation] is a string literal naming it in the log line. */
    suspend fun <T> run(operation: String, block: suspend () -> T): T
}
