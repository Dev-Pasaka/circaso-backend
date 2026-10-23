package com.circaso.users.presentation.services

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.api.PublicProfile
import com.circaso.users.domain.models.HandleCheck
import com.circaso.users.domain.models.MyProfile
import com.circaso.users.domain.usecases.CheckHandle
import com.circaso.users.domain.usecases.ClaimHandle
import com.circaso.users.domain.usecases.LookupHandle

/** @handles: availability, claiming, lookup. */
class HandleService(
    private val checkHandle: CheckHandle,
    private val claimHandle: ClaimHandle,
    private val lookupHandle: LookupHandle,
) {
    suspend fun check(userId: UserId, handle: String): Resource<HandleCheck> = checkHandle(userId, handle)
    suspend fun claim(userId: UserId, handle: String): Resource<MyProfile> = claimHandle(userId, handle)
    suspend fun getByHandle(viewer: UserId, handle: String): Resource<PublicProfile> = lookupHandle(viewer, handle)
}
