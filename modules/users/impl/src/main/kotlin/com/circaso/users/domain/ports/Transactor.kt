package com.circaso.users.domain.ports

/**
 * One operation, one transaction (decision 2026-10-27, PG0 review): a use case's reads that a write depends on, the
 * write and its outbox row all run inside [run]. Repositories called inside it join the open transaction. The block
 * holds repository calls only; Redis, HTTP and publishing come after it returns.
 */
interface Transactor {
    /** Runs [block] in one transaction; [operation] is a string literal naming it in the log line. */
    suspend fun <T> run(operation: String, block: suspend () -> T): T
}
