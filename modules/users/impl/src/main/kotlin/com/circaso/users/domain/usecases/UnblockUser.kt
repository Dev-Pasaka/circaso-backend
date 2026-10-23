package com.circaso.users.domain.usecases

import com.circaso.shared.EventBus
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.envelope
import com.circaso.users.api.UserUnblocked
import com.circaso.users.domain.models.Block
import com.circaso.users.domain.ports.ReachCache
import com.circaso.users.domain.repositories.BlockRepository
import com.circaso.users.domain.repositories.UsersChanges
import com.circaso.users.domain.repositories.UsersStore
import java.time.Clock

/** U14: lifts a block. The contact, if there was one, is exactly as it was. */
class UnblockUser(
    private val blocks: BlockRepository,
    private val store: UsersStore,
    private val bus: EventBus,
    private val cache: ReachCache,
    private val clock: Clock,
) {
    suspend operator fun invoke(userId: UserId, target: UserId): Resource<Unit> {
        if (!blocks.blocked(userId, target)) return Resource.success(Unit)
        store.commit(
            UsersChanges(
                unblocks = listOf(Block(userId, target, clock.instant())),
                events = listOf(bus.envelope(UserUnblocked(userId, target))),
            ),
        )
        cache.invalidateTarget(target)
        cache.invalidateTarget(userId)
        return Resource.success(Unit)
    }
}
