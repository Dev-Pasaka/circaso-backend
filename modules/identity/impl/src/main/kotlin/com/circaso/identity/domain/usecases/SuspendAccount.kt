package com.circaso.identity.domain.usecases

import com.circaso.identity.api.AccountStatus
import com.circaso.identity.api.AccountStatusChanged
import com.circaso.identity.domain.errors.IdentityError
import com.circaso.identity.domain.models.AccountRecord
import com.circaso.identity.domain.models.SecurityEvent
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.domain.ports.IdentityCache
import com.circaso.identity.domain.ports.Transactor
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.identity.domain.repositories.ProviderRepository
import com.circaso.identity.domain.repositories.SessionRepository
import com.circaso.shared.EventBus
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.envelope

/**
 * Staff suspend an account (decision 2026-11-08), modelled on I14. Under the account's lock, in one transaction: the
 * account turns `SUSPENDED`, every active session ends, a `SUSPENDED` line goes in its security log and
 * `identity.AccountStatusChanged` in the outbox. After the commit the event is published and every ended session goes
 * on the gateway's deny-list, so access tokens already handed out stop at once rather than in up to 15 minutes.
 *
 * What follows from the event is the other modules': the gateway closes the account's sockets, users hides the
 * profile, and the wallet's sender rule refuses its payments (`wallet.account_restricted`). Identity itself refuses
 * the way back in: I2 answers `identity.account_suspended`, I6 the same, and I7 ends the refresh.
 *
 * Only an `ACTIVE` account can be suspended (`identity.account_not_active`): a pending one has nothing to stop yet, and
 * a deleted, blocked or recovering one is already in a state with its own rules. The staff member who did it is the
 * ops module's audit log's to record; identity's own log line says only what happened.
 */
class SuspendAccount(
    private val transactor: Transactor,
    private val accounts: AccountRepository,
    private val providers: ProviderRepository,
    private val sessions: SessionRepository,
    private val store: IdentityStore,
    private val cache: IdentityCache,
    private val bus: EventBus,
) {
    suspend operator fun invoke(userId: UserId): Resource<AccountRecord> {
        val statusChanged = bus.envelope(AccountStatusChanged(userId, AccountStatus.SUSPENDED))
        val suspension =
            transactor.attempt("accounts.suspend") {
                val account = accounts.lock(userId) ?: return@attempt Resource.error(IdentityError.AccountNotFound)
                if (account.status !=
                    AccountStatus.ACTIVE
                ) {
                    return@attempt Resource.error(IdentityError.AccountNotActive)
                }
                val ended = sessions.revokeAll(userId)
                val suspended = account.copy(status = AccountStatus.SUSPENDED)
                val changes =
                    Changes(
                        accounts = listOf(suspended),
                        securityEvents = listOf(SecurityEvent(userId, null, SecurityEventType.SUSPENDED, null, null)),
                        events = listOf(statusChanged),
                    )
                store.write(changes)
                Resource.success(suspended to ended)
            }
        val (account, ended) =
            when (suspension) {
                is Resource.Success -> suspension.data
                is Resource.Error -> return suspension
            }
        store.publish(Changes(events = listOf(statusChanged)))
        ended.forEach { cache.revoke(it) }
        return Resource.success(AccountRecord(account, providers.links(userId)))
    }
}
