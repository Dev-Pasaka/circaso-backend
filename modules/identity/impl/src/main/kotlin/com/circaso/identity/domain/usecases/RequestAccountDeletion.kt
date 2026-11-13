package com.circaso.identity.domain.usecases

import com.circaso.identity.api.Principal
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.support.DeletionScheduler
import com.circaso.shared.Resource
import com.circaso.shared.SharedError

/**
 * I14: schedules the deletion once every `DeletionGuard` agrees (the wallet refuses while it holds funds; its
 * own error code reaches the app). The account turns `DELETED`, with `deleteAfter` set to the end of its grace
 * period, and its session ends at once. The rule itself is [DeletionScheduler]'s, shared with the website (I17).
 *
 * The account stays recoverable for 30 days (`identity.deletion-grace-days`, decision 2026-10-27); until then only
 * `AccountStatusChanged` goes out. At `deleteAfter`, `CompleteDeletions` deletes its rows and publishes
 * `AccountDeleted`. Asking again while it is `DELETED` changes nothing and publishes nothing.
 *
 * TODO(decision: how a deleted account is restored during its 30 days, and by whom) there is no restore path yet.
 */
class RequestAccountDeletion(private val accounts: AccountRepository, private val deletions: DeletionScheduler) {
    suspend operator fun invoke(principal: Principal): Resource<Unit> {
        accounts.find(principal.userId) ?: return Resource.error(SharedError.NotFound)
        return when (val outcome = deletions.schedule(principal.userId, principal.sessionId)) {
            is DeletionScheduler.Outcome.Refused -> {
                outcome.error
            }

            DeletionScheduler.Outcome.NotFound -> {
                Resource.error(SharedError.NotFound)
            }

            is DeletionScheduler.Outcome.Scheduled, is DeletionScheduler.Outcome.AlreadyScheduled -> {
                Resource.success(Unit)
            }
        }
    }
}
