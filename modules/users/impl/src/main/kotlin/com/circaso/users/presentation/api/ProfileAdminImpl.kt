package com.circaso.users.presentation.api

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.getOrNull
import com.circaso.shared.map
import com.circaso.users.api.AdminProfile
import com.circaso.users.api.ProfileAdmin
import com.circaso.users.api.ProfileIdPage
import com.circaso.users.domain.models.AdminProfileView
import com.circaso.users.domain.models.ProfileIdResults
import com.circaso.users.presentation.services.ProfileAdminService

/**
 * users:api's staff contract (decision 2026-11-08), bound for the ops module only: the app gateway never mounts it.
 * Every call goes through [ProfileAdminService], like any other way in.
 */
class ProfileAdminImpl(private val admin: ProfileAdminService) : ProfileAdmin {
    override suspend fun profiles(ids: Set<UserId>): Map<UserId, AdminProfile> =
        admin
            .profiles(ids)
            .getOrNull()
            .orEmpty()
            .mapValues { (_, view) -> view.toAdmin() }

    override suspend fun searchByHandle(
        prefix: String,
        cursor: String?,
        limit: Int,
    ): Resource<ProfileIdPage> = admin.searchByHandle(prefix, cursor, limit).map { it.toPage() }

    override suspend fun searchByName(
        prefix: String,
        cursor: String?,
        limit: Int,
    ): Resource<ProfileIdPage> = admin.searchByName(prefix, cursor, limit).map { it.toPage() }

    private fun AdminProfileView.toAdmin() =
        AdminProfile(
            userId = profile.userId,
            displayName = profile.displayName,
            handle = profile.handle,
            about = profile.about,
            avatarUrl = avatarUrl,
            support = profile.support,
            createdAt = profile.createdAt,
        )

    private fun ProfileIdResults.toPage() = ProfileIdPage(userIds, nextCursor)
}
