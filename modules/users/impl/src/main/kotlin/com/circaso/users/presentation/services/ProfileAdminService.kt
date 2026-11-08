package com.circaso.users.presentation.services

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.domain.models.AdminProfileView
import com.circaso.users.domain.models.ProfileIdResults
import com.circaso.users.domain.usecases.GetAdminProfiles
import com.circaso.users.domain.usecases.SearchProfilesByHandle
import com.circaso.users.domain.usecases.SearchProfilesByName

/** Staff on the ops gateway (decision 2026-11-08): profiles whatever their status, and search by handle or name. */
class ProfileAdminService(
    private val getAdminProfiles: GetAdminProfiles,
    private val searchProfilesByHandle: SearchProfilesByHandle,
    private val searchProfilesByName: SearchProfilesByName,
) {
    suspend fun profiles(ids: Set<UserId>): Resource<Map<UserId, AdminProfileView>> = getAdminProfiles(ids)

    suspend fun searchByHandle(
        prefix: String,
        cursor: String?,
        limit: Int,
    ): Resource<ProfileIdResults> = searchProfilesByHandle(prefix, cursor, limit)

    suspend fun searchByName(
        prefix: String,
        cursor: String?,
        limit: Int,
    ): Resource<ProfileIdResults> = searchProfilesByName(prefix, cursor, limit)
}
