package com.circaso.identity.domain.usecases

import com.circaso.identity.api.AccountStatus
import com.circaso.identity.api.AccountStatusChanged
import com.circaso.identity.api.DeletionGuard
import com.circaso.identity.api.Principal
import com.circaso.identity.domain.models.SecurityEvent
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.domain.models.SessionStatus
import com.circaso.identity.domain.ports.IdentityCache
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.identity.domain.repositories.SessionRepository
import com.circaso.shared.EventBus
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.envelope

/**
 * I14: schedules the deletion once every [DeletionGuard] agrees (the wallet refuses while it holds funds; its
 * own error code reaches the app). The account turns `DELETED` and its session ends at once.
 *
 * TODO(decision: how long a deleted account stays recoverable, and what finishes the deletion) `AccountDeleted`
 * is published when that grace ends; until then only `AccountStatusChanged` goes out.
 */
class RequestAccountDeletion(
    private val accounts: AccountRepository,
    private val sessions: SessionRepository,
    private val guards: List<DeletionGuard>,
    private val store: IdentityStore,
    private val cache: IdentityCache,
    private val bus: EventBus,
) {
    suspend operator fun invoke(principal: Principal): Resource<Unit> {
        val account = accounts.find(principal.userId) ?: return Resource.error(SharedError.NotFound)
        for (guard in guards) {
            val answer = guard.canDelete(principal.userId)
            if (answer is Resource.Error) return answer
        }
        val session = sessions.find(principal.sessionId)?.takeIf { it.status == SessionStatus.ACTIVE }
        store.commit(
            Changes(
                accounts = listOf(account.copy(status = AccountStatus.DELETED)),
                sessions = listOfNotNull(session?.copy(status = SessionStatus.REVOKED)),
                securityEvents =
                    listOf(
                        SecurityEvent(principal.userId, null, SecurityEventType.DELETION_REQUESTED, null, null),
                    ),
                events = listOf(bus.envelope(AccountStatusChanged(principal.userId, AccountStatus.DELETED))),
            ),
        )
        cache.revoke(principal.sessionId)
        return Resource.success(Unit)
    }
}
