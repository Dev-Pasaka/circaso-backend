package com.circaso.users.presentation.services

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.api.PublicProfile
import com.circaso.users.domain.models.MyProfile
import com.circaso.users.domain.usecases.GetMyProfile
import com.circaso.users.domain.usecases.ListBlocks

/** What Asha's read-only agent routes read about the caller (decision 2026-10-31): reads only, own data only. */
class UsersAgentService(private val getMyProfile: GetMyProfile, private val listBlocks: ListBlocks) {
    suspend fun getProfile(userId: UserId): Resource<MyProfile> = getMyProfile(userId)

    suspend fun listBlocks(userId: UserId): Resource<List<PublicProfile>> = listBlocks.invoke(userId)
}
