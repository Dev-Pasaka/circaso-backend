package com.circaso.identity.domain.usecases

import com.circaso.identity.api.AccountStatus
import com.circaso.identity.api.AccountStatusChanged
import com.circaso.identity.api.DeletionGuard
import com.circaso.identity.api.Principal
import com.circaso.identity.domain.models.SecurityEvent
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.domain.ports.IdentityCache
import com.circaso.identity.domain.ports.Transactor
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.identity.domain.repositories.SessionRepository
import com.circaso.identity.domain.support.IdentityConfig
import com.circaso.shared.EventBus
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.envelope
import java.time.Clock

/**
 * I14: schedules the deletion once every [DeletionGuard] agrees (the wallet refuses while it holds funds; its
 * own error code reaches the app). The account turns `DELETED`, with `deleteAfter` set to the end of its grace
 * period, and its session ends at once.
 *
 * The account stays recoverable for 30 days (`identity.deletion-grace-days`, decision 2026-10-27); until then only
 * `AccountStatusChanged` goes out. At `deleteAfter`, `CompleteDeletions` deletes its rows and publishes
 * `AccountDeleted`. Asking again while it is `DELETED` changes nothing and publishes nothing.
 *
 * TODO(decision: how a deleted account is restored during its 30 days, and by whom) there is no restore path yet.
 */
class RequestAccountDeletion(
    private val transactor: Transactor,
    private val accounts: AccountRepository,
    private val sessions: SessionRepository,
    private val guards: List<DeletionGuard>,
    private val store: IdentityStore,
    private val cache: IdentityCache,
    private val bus: EventBus,
    private val config: IdentityConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(principal: Principal): Resource<Unit> {
        accounts.find(principal.userId) ?: return Resource.error(SharedError.NotFound)
        for (guard in guards) {
            val answer = guard.canDelete(principal.userId)
            if (answer is Resource.Error) return answer
        }
        val statusChanged = bus.envelope(AccountStatusChanged(principal.userId, AccountStatus.DELETED))
        val deleteAfter = clock.instant().plus(config.deletionGrace)
        val deletion =
            transactor.attempt("accounts.request-deletion") {
                val account = accounts.lock(principal.userId) ?: return@attempt Resource.error(SharedError.NotFound)
                // A repeat: the deletion and its grace period are already running, so nothing changes.
                if (account.status == AccountStatus.DELETED) return@attempt Resource.success(Changes())
                sessions.revoke(principal.sessionId)
                val changes =
                    Changes(
                        accounts = listOf(account.copy(status = AccountStatus.DELETED, deleteAfter = deleteAfter)),
                        securityEvents =
                            listOf(
                                SecurityEvent(principal.userId, null, SecurityEventType.DELETION_REQUESTED, null, null),
                            ),
                        events = listOf(statusChanged),
                    )
                store.write(changes)
                Resource.success(changes)
            }
        val changes = (deletion as? Resource.Success)?.data ?: return Resource.error(SharedError.NotFound)
        store.publish(changes)
        cache.revoke(principal.sessionId)
        return Resource.success(Unit)
    }
}
