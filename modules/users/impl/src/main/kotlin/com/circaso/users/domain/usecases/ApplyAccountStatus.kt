package com.circaso.users.domain.usecases

import com.circaso.identity.api.AccountStatusChanged
import com.circaso.shared.Resource
import com.circaso.users.domain.ports.ReachCache
import com.circaso.users.domain.repositories.ProfileRepository
import com.circaso.users.domain.support.ProfileProvisioner
import java.time.Instant

/**
 * `identity.AccountStatusChanged`: hides the profile while the account isn't active (deleted in its grace period,
 * suspended, blocked), so nobody can look it up, connect to it or see it in a contact list, and restores it when the
 * account is active again. [occurredAt] is the event's time: a late, older event changes nothing, so retries and
 * reordering are safe. Cached reach decisions about the account are forgotten either way.
 */
class ApplyAccountStatus(
    private val provisioner: ProfileProvisioner,
    private val profiles: ProfileRepository,
    private val cache: ReachCache,
) {
    suspend operator fun invoke(event: AccountStatusChanged, occurredAt: Instant): Resource<Unit> {
        // A status that arrives before the profile exists still has to hide it once it does.
        provisioner.ensure(event.userId)
        profiles.recordAccountStatus(event.userId, event.status, occurredAt)
        cache.invalidateTarget(event.userId)
        return Resource.success(Unit)
    }
}
