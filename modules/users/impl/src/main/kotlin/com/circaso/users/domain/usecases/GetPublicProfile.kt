package com.circaso.users.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.users.api.PublicProfile
import com.circaso.users.domain.errors.UsersError
import com.circaso.users.domain.repositories.ProfileRepository
import com.circaso.users.domain.support.ProfileViews
import com.circaso.users.domain.support.ReachRule

/** U10: the public profile of someone the caller can reach (or of themselves). */
class GetPublicProfile(
    private val reach: ReachRule,
    private val profiles: ProfileRepository,
    private val views: ProfileViews,
) {
    suspend operator fun invoke(viewer: UserId, target: UserId): Resource<PublicProfile> {
        if (viewer != target && !reach.canReach(viewer, target)) return Resource.error(UsersError.NotReachable)
        val profile = profiles.find(target) ?: return Resource.error(SharedError.NotFound)
        return Resource.success(views.forViewer(viewer, profile))
    }
}
