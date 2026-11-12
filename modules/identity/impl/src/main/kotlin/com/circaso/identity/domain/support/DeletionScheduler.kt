package com.circaso.identity.domain.support

import com.circaso.identity.api.AccountStatus
import com.circaso.identity.api.AccountStatusChanged
import com.circaso.identity.api.DeletionGuard
import com.circaso.identity.api.SessionId
import com.circaso.identity.domain.models.DeletionFeedback
import com.circaso.identity.domain.models.SecurityEvent
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.domain.ports.IdentityCache
import com.circaso.identity.domain.ports.Transactor
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.identity.domain.repositories.SessionRepository
import com.circaso.shared.EventBus
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.envelope
import java.time.Clock
import java.time.Instant

/**
 * The rule every way of asking for a deletion shares (I14 in the app, I17 on the website): every [DeletionGuard] is
 * asked first (the wallet refuses while it holds funds, with its own error code); then, under the account's lock and
 * in one transaction, the account turns `DELETED` with `deleteAfter` at the end of its grace period, sessions end, a
 * `DELETION_REQUESTED` line goes in its security log and `identity.AccountStatusChanged` in the outbox. After the
 * commit the event is published and the ended sessions go on the gateway's deny-list.
 *
 * An account already `DELETED` is a repeat: its deletion and grace period are running, so nothing changes.
 */
class DeletionScheduler(
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
    sealed interface Outcome {
        data class Scheduled(val deleteAfter: Instant) : Outcome

        data class AlreadyScheduled(val deleteAfter: Instant?) : Outcome

        /** A guard said no; its error, cause included, as it gave it. */
        data class Refused(val error: Resource.Error) : Outcome

        data object NotFound : Outcome
    }

    /**
     * Schedules [userId]'s deletion. [onlySession] ends just that session (the app's own, I14); null ends every
     * session of the account (I17, where no session asks). [feedback] is stored with it, unlinked from the account;
     * [source] goes in the security log line.
     */
    suspend fun schedule(
        userId: UserId,
        onlySession: SessionId?,
        feedback: DeletionFeedback? = null,
        source: String? = null,
    ): Outcome {
        for (guard in guards) {
            val answer = guard.canDelete(userId)
            if (answer is Resource.Error) return Outcome.Refused(answer)
        }
        val statusChanged = bus.envelope(AccountStatusChanged(userId, AccountStatus.DELETED))
        val deleteAfter = clock.instant().plus(config.deletionGrace)
        val result =
            transactor.attempt("accounts.request-deletion") {
                val account = accounts.lock(userId) ?: return@attempt Resource.success(Decision.Missing)
                if (account.status == AccountStatus.DELETED) {
                    return@attempt Resource.success(Decision.Repeat(account.deleteAfter))
                }
                val ended =
                    if (onlySession != null) {
                        sessions.revoke(onlySession)
                        emptyList()
                    } else {
                        sessions.revokeAll(userId)
                    }
                val changes =
                    Changes(
                        accounts = listOf(account.copy(status = AccountStatus.DELETED, deleteAfter = deleteAfter)),
                        securityEvents =
                            listOf(SecurityEvent(userId, null, SecurityEventType.DELETION_REQUESTED, null, source)),
                        deletionFeedback = listOfNotNull(feedback),
                        events = listOf(statusChanged),
                    )
                store.write(changes)
                Resource.success(Decision.Done(changes, ended))
            }
        val decision = (result as? Resource.Success)?.data ?: Decision.Missing
        // The app's own session is denied even on a repeat, as I14 always did.
        onlySession?.let { cache.revoke(it) }
        return when (decision) {
            Decision.Missing -> {
                Outcome.NotFound
            }

            is Decision.Repeat -> {
                Outcome.AlreadyScheduled(decision.deleteAfter)
            }

            is Decision.Done -> {
                store.publish(decision.changes)
                decision.ended.forEach { cache.revoke(it) }
                Outcome.Scheduled(deleteAfter)
            }
        }
    }

    private sealed interface Decision {
        data object Missing : Decision

        data class Repeat(val deleteAfter: Instant?) : Decision

        data class Done(val changes: Changes, val ended: List<SessionId>) : Decision
    }
}
