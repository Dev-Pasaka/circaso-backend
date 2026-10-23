package com.circaso.users.presentation.services

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.api.PublicProfile
import com.circaso.users.domain.ports.LoggedFrame
import com.circaso.users.domain.usecases.CanReach
import com.circaso.users.domain.usecases.FilterReachable
import com.circaso.users.domain.usecases.GetProfiles
import com.circaso.users.domain.usecases.ReplayFrames

/** What other modules ask of users through its `api`, and the replay the gateway asks for after a reconnect. */
class DirectoryService(
    private val canReach: CanReach,
    private val filterReachable: FilterReachable,
    private val getProfiles: GetProfiles,
    private val replayFrames: ReplayFrames,
) {
    suspend fun canReach(from: UserId, to: UserId): Resource<Boolean> = canReach.invoke(from, to)
    suspend fun filterReachable(from: UserId, to: Set<UserId>): Resource<Set<UserId>> = filterReachable.invoke(from, to)
    suspend fun getProfiles(ids: Set<UserId>): Resource<Map<UserId, PublicProfile>> = getProfiles.invoke(ids)
    suspend fun getMissedFrames(userId: UserId, seq: Long): Resource<List<LoggedFrame>?> = replayFrames(userId, seq)
}
