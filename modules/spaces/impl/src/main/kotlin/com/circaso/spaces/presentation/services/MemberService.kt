package com.circaso.spaces.presentation.services

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.spaces.api.SpaceId
import com.circaso.spaces.domain.models.MemberView
import com.circaso.spaces.domain.models.MembersQuery
import com.circaso.spaces.domain.models.MembersView
import com.circaso.spaces.domain.models.MuteDuration
import com.circaso.spaces.domain.models.Role
import com.circaso.spaces.domain.usecases.ListMembers
import com.circaso.spaces.domain.usecases.MuteMember
import com.circaso.spaces.domain.usecases.RemoveMember
import com.circaso.spaces.domain.usecases.SetBan
import com.circaso.spaces.domain.usecases.SetRole

/** Members, roles and moderation: SP23-SP27. */
class MemberService(
    private val listMembers: ListMembers,
    private val setRole: SetRole,
    private val muteMember: MuteMember,
    private val removeMember: RemoveMember,
    private val setBan: SetBan,
) {
    suspend fun members(query: MembersQuery): Resource<MembersView> = listMembers(query)

    suspend fun role(
        caller: UserId,
        spaceId: SpaceId,
        target: UserId,
        role: Role,
    ): Resource<MemberView> = setRole(caller, spaceId, target, role)

    /** [duration] null unmutes. */
    suspend fun mute(
        caller: UserId,
        spaceId: SpaceId,
        target: UserId,
        duration: MuteDuration?,
    ): Resource<MemberView> = muteMember(caller, spaceId, target, duration)

    suspend fun remove(
        caller: UserId,
        spaceId: SpaceId,
        target: UserId,
    ): Resource<Unit> = removeMember(caller, spaceId, target)

    suspend fun ban(
        caller: UserId,
        spaceId: SpaceId,
        target: UserId,
        banned: Boolean,
    ): Resource<Boolean> = setBan(caller, spaceId, target, banned)
}
