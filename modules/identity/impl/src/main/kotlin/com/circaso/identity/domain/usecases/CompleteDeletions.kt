package com.circaso.identity.domain.usecases

import com.circaso.identity.api.AccountDeleted
import com.circaso.identity.domain.ports.Transactor
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.shared.EventBus
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.envelope
import java.time.Clock
import java.time.Instant

/**
 * The scheduled end of an account deletion (decision 2026-10-27: a deleted account stays recoverable for 30 days):
 * once an account's `deleteAfter` has passed, its identity rows are deleted and `identity.AccountDeleted` is written to
 * the outbox in the same transaction, one account per transaction; every other module then deletes that user's data.
 *
 * Safe to run twice and on two nodes at once: the delete only takes an account still `DELETED` and due, so a second
 * run, or the losing node, finds nothing and writes no event. An account restored before its `deleteAfter` is no
 * longer `DELETED` and is left alone. Returns how many accounts it completed.
 */
class CompleteDeletions(
    private val transactor: Transactor,
    private val accounts: AccountRepository,
    private val store: IdentityStore,
    private val bus: EventBus,
    private val clock: Clock,
) {
    suspend operator fun invoke(): Resource<Int> {
        val now = clock.instant()
        var completed = 0
        while (true) {
            val due = accounts.dueForDeletion(now, BATCH)
            completed += due.count { complete(it, now) }
            if (due.size < BATCH) break
        }
        return Resource.success(completed)
    }

    private suspend fun complete(userId: UserId, now: Instant): Boolean {
        val deleted = Changes(events = listOf(bus.envelope(AccountDeleted(userId))))
        val done =
            transactor.run("accounts.complete-deletion") {
                val gone = accounts.completeDeletion(userId, now)
                if (gone) store.write(deleted)
                gone
            }
        if (done) store.publish(deleted)
        return done
    }

    private companion object {
        const val BATCH = 100
    }
}
