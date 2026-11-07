package com.circaso.identity.domain.usecases

import com.circaso.identity.api.AccountStatus
import com.circaso.identity.api.AccountStatusChanged
import com.circaso.identity.domain.errors.IdentityError
import com.circaso.identity.domain.models.AccountRecord
import com.circaso.identity.domain.models.SecurityEvent
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.domain.ports.Transactor
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.identity.domain.repositories.ProviderRepository
import com.circaso.shared.EventBus
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.envelope

/**
 * Staff give a suspended account back (decision 2026-11-08): `ACTIVE` again, an `UNSUSPENDED` line in its security
 * log and `identity.AccountStatusChanged`, which shows the profile again and lifts the wallet's sender rule. The
 * sessions the suspension ended stay ended: the person signs in again, on the phone they had, with its device proof.
 * Only a `SUSPENDED` account can be given back (`identity.account_not_suspended`).
 */
class UnsuspendAccount(
    private val transactor: Transactor,
    private val accounts: AccountRepository,
    private val providers: ProviderRepository,
    private val store: IdentityStore,
    private val bus: EventBus,
) {
    suspend operator fun invoke(userId: UserId): Resource<AccountRecord> {
        val statusChanged = bus.envelope(AccountStatusChanged(userId, AccountStatus.ACTIVE))
        val restored =
            transactor.attempt("accounts.unsuspend") {
                val account = accounts.lock(userId) ?: return@attempt Resource.error(IdentityError.AccountNotFound)
                if (account.status != AccountStatus.SUSPENDED) {
                    return@attempt Resource.error(IdentityError.AccountNotSuspended)
                }
                val active = account.copy(status = AccountStatus.ACTIVE)
                val changes =
                    Changes(
                        accounts = listOf(active),
                        securityEvents = listOf(SecurityEvent(userId, null, SecurityEventType.UNSUSPENDED, null, null)),
                        events = listOf(statusChanged),
                    )
                store.write(changes)
                Resource.success(active to changes)
            }
        val (account, changes) =
            when (restored) {
                is Resource.Success -> restored.data
                is Resource.Error -> return restored
            }
        store.publish(changes)
        return Resource.success(AccountRecord(account, providers.links(userId)))
    }
}
