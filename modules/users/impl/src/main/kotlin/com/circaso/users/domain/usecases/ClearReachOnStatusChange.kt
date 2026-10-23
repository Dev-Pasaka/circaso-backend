package com.circaso.users.domain.usecases

import com.circaso.identity.api.AccountStatusChanged
import com.circaso.shared.Resource
import com.circaso.users.domain.ports.ReachCache

/**
 * `identity.AccountStatusChanged`: forgets cached reach decisions about the account. An account that is not active
 * cannot be reached, so it drops out of every contact list as soon as the cache is clear; its profile is kept
 * and comes back when the account does.
 */
class ClearReachOnStatusChange(private val cache: ReachCache) {
    suspend operator fun invoke(event: AccountStatusChanged): Resource<Unit> {
        cache.invalidateTarget(event.userId)
        return Resource.success(Unit)
    }
}
