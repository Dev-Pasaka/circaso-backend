package com.circaso.users.presentation.services

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.api.PublicProfile
import com.circaso.users.domain.usecases.BlockUser
import com.circaso.users.domain.usecases.CheckReach
import com.circaso.users.domain.usecases.ListBlocks
import com.circaso.users.domain.usecases.UnblockUser

/** Blocks and the reach check. */
class BlockService(
    private val listBlocks: ListBlocks,
    private val blockUser: BlockUser,
    private val unblockUser: UnblockUser,
    private val checkReach: CheckReach,
) {
    suspend fun listBlocks(userId: UserId): Resource<List<PublicProfile>> = listBlocks.invoke(userId)
    suspend fun block(userId: UserId, target: UserId): Resource<Unit> = blockUser(userId, target)
    suspend fun unblock(userId: UserId, target: UserId): Resource<Unit> = unblockUser(userId, target)
    suspend fun checkReach(from: UserId, to: UserId): Resource<Boolean> = checkReach.invoke(from, to)
}
