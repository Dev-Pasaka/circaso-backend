package com.circaso.messaging.domain.ports

import com.circaso.shared.Resource

/**
 * One operation, one transaction (decision 2026-10-27, PG0 review): a use case's reads that a write depends on, the
 * write and its outbox row all run inside [run] or [attempt]. Repositories called inside join the open transaction.
 * The block holds repository calls only; Redis, HTTP, frames and publishing come after it returns.
 */
interface Transactor {
    /** Runs [block] in one transaction; [operation] is a string literal naming it in the log line. */
    suspend fun <T> run(operation: String, block: suspend () -> T): T

    /**
     * Like [run], for an operation that can be refused part-way: a block that returns `Resource.Error` rolls the
     * whole transaction back, so a step that already wrote leaves nothing behind, and the error is the answer.
     */
    suspend fun <T> attempt(operation: String, block: suspend () -> Resource<T>): Resource<T>
}
