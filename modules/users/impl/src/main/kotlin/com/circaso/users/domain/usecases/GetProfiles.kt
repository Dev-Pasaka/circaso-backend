package com.circaso.users.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.api.PublicProfile
import com.circaso.users.domain.repositories.ProfileRepository
import com.circaso.users.domain.support.ProfileViews

/** For other modules' display data: name, avatar and handle. */
class GetProfiles(private val profiles: ProfileRepository, private val views: ProfileViews) {
    suspend operator fun invoke(ids: Set<UserId>): Resource<Map<UserId, PublicProfile>> =
        Resource.success(profiles.findAll(ids).mapValues { views.forModules(it.value) })
}
