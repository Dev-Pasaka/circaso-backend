package com.circaso.users.presentation.services

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.api.PublicProfile
import com.circaso.users.domain.ports.LoggedFrame
import com.circaso.users.domain.usecases.CanReach
import com.circaso.users.domain.usecases.CheckBlockedEitherWay
import com.circaso.users.domain.usecases.CheckSupportHandle
import com.circaso.users.domain.usecases.FilterReachable
import com.circaso.users.domain.usecases.GetProfiles
import com.circaso.users.domain.usecases.ListContactIds
import com.circaso.users.domain.usecases.ReplayFrames

/**
 * What other modules ask of users through its `api` (and identity through `SupportProfiles`), and the replay the
 * gateway asks for after a reconnect.
 */
class DirectoryService(
    private val canReach: CanReach,
    private val filterReachable: FilterReachable,
    private val getProfiles: GetProfiles,
    private val replayFrames: ReplayFrames,
    private val checkBlockedEitherWay: CheckBlockedEitherWay,
    private val checkSupportHandle: CheckSupportHandle,
    private val listContactIds: ListContactIds,
) {
    suspend fun canReach(from: UserId, to: UserId): Resource<Boolean> = canReach.invoke(from, to)

    suspend fun filterReachable(from: UserId, to: Set<UserId>): Resource<Set<UserId>> = filterReachable.invoke(from, to)

    suspend fun blockedEitherWay(a: UserId, b: UserId): Resource<Boolean> = checkBlockedEitherWay(a, b)

    suspend fun getProfiles(ids: Set<UserId>): Resource<Map<UserId, PublicProfile>> = getProfiles.invoke(ids)

    suspend fun contactIds(userId: UserId, limit: Int): Resource<Set<UserId>> = listContactIds(userId, limit)

    suspend fun checkSupportHandle(handle: String): Resource<String> = checkSupportHandle.invoke(handle)

    suspend fun getMissedFrames(userId: UserId, seq: Long): Resource<List<LoggedFrame>?> = replayFrames(userId, seq)
}
