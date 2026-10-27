package com.circaso.users.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.users.api.PublicProfile
import com.circaso.users.domain.errors.UsersError
import com.circaso.users.domain.ports.UsersCache
import com.circaso.users.domain.repositories.BlockRepository
import com.circaso.users.domain.repositories.HandleRepository
import com.circaso.users.domain.repositories.ProfileRepository
import com.circaso.users.domain.support.HandleRules
import com.circaso.users.domain.support.ProfileViews

/**
 * U9: an exact-match lookup, 30 per user per hour. A handle that doesn't exist, is on hold, belongs to someone
 * who turned lookup off or who blocked the caller all answer `users.handle_not_found`, so none can be told apart.
 */
class LookupHandle(
    private val handles: HandleRepository,
    private val profiles: ProfileRepository,
    private val blocks: BlockRepository,
    private val views: ProfileViews,
    private val usersCache: UsersCache,
) {
    suspend operator fun invoke(viewer: UserId, raw: String): Resource<PublicProfile> {
        if (usersCache.countHandleLookup(viewer) > MAX_PER_HOUR) {
            return Resource.error(SharedError.RateLimited(usersCache.handleLookupRetryAfter(viewer)?.seconds ?: 3_600))
        }
        val handle = HandleRules.normalise(raw) ?: return Resource.error(UsersError.HandleNotFound)
        val claim =
            handles.find(handle)?.takeIf { it.releasedAt == null } ?: return Resource.error(UsersError.HandleNotFound)
        val profile =
            profiles.find(claim.userId)?.takeIf { it.privacy.handleLookup && !it.hidden }
                ?: return Resource.error(UsersError.HandleNotFound)
        if (claim.userId != viewer &&
            blocks.eitherWay(viewer, claim.userId)
        ) {
            return Resource.error(UsersError.HandleNotFound)
        }
        return Resource.success(views.forViewer(viewer, profile))
    }

    private companion object {
        const val MAX_PER_HOUR = 30
    }
}
