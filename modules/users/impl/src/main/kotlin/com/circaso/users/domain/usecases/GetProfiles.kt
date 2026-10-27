package com.circaso.users.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.api.PublicProfile
import com.circaso.users.domain.repositories.ProfileRepository
import com.circaso.users.domain.support.ProfileViews
import com.circaso.users.domain.support.fallbackProfile

/**
 * For other modules' display data: name, avatar and handle. Someone whose account isn't active shows as the plain
 * fallback (no name, handle or picture), so an old chat doesn't keep showing a deleted account.
 */
class GetProfiles(private val profiles: ProfileRepository, private val views: ProfileViews) {
    suspend operator fun invoke(ids: Set<UserId>): Resource<Map<UserId, PublicProfile>> =
        Resource.success(
            profiles.findAll(ids).mapValues { (id, profile) ->
                if (profile.hidden) fallbackProfile(id) else views.forModules(profile)
            },
        )
}
