package com.circaso.users.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.domain.models.AdminProfileView
import com.circaso.users.domain.repositories.ProfileRepository
import com.circaso.users.domain.support.ProfileViews

/**
 * Staff (decision 2026-11-08): the profiles among [ids], hidden ones too (a suspended or deleted account's), each
 * with its photo signed for a short while. Unlike [GetProfiles], no fallback and no privacy: staff see what is stored.
 */
class GetAdminProfiles(private val profiles: ProfileRepository, private val views: ProfileViews) {
    suspend operator fun invoke(ids: Set<UserId>): Resource<Map<UserId, AdminProfileView>> {
        val wanted = ids.take(MAX_IDS).toSet()
        return Resource.success(
            profiles.findAll(wanted).mapValues { (_, profile) -> AdminProfileView(profile, views.avatarUrl(profile)) },
        )
    }

    private companion object {
        /** A page of search results at most; the ops module never asks for more at once. */
        const val MAX_IDS = 500
    }
}
