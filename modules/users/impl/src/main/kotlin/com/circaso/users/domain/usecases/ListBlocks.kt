package com.circaso.users.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.api.PublicProfile
import com.circaso.users.domain.repositories.BlockRepository
import com.circaso.users.domain.repositories.ProfileRepository
import com.circaso.users.domain.support.ProfileViews
import com.circaso.users.domain.support.fallbackProfile

/** U13: the people this user has blocked. */
class ListBlocks(
    private val blocks: BlockRepository,
    private val profiles: ProfileRepository,
    private val views: ProfileViews,
) {
    suspend operator fun invoke(userId: UserId): Resource<List<PublicProfile>> {
        val blocked = blocks.list(userId)
        val found = profiles.findAll(blocked.map { it.blockedId }.toSet())
        return Resource.success(
            blocked.map {
                found[it.blockedId]?.let { p -> views.forViewer(userId, p) }
                    ?: fallbackProfile(it.blockedId)
            },
        )
    }
}
