package com.circaso.users.domain.usecases

import com.circaso.shared.EventBus
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.envelope
import com.circaso.users.api.UserBlocked
import com.circaso.users.domain.errors.UsersError
import com.circaso.users.domain.models.Block
import com.circaso.users.domain.ports.ReachCache
import com.circaso.users.domain.repositories.BlockRepository
import com.circaso.users.domain.repositories.UsersChanges
import com.circaso.users.domain.repositories.UsersStore
import java.time.Clock

/** U14: blocks someone. A block overrides every other way of reaching a person, in both directions. */
class BlockUser(
    private val blocks: BlockRepository,
    private val store: UsersStore,
    private val bus: EventBus,
    private val cache: ReachCache,
    private val clock: Clock,
) {
    suspend operator fun invoke(userId: UserId, target: UserId): Resource<Unit> {
        if (userId == target) return Resource.error(UsersError.CannotBlockSelf)
        if (blocks.blocked(userId, target)) return Resource.success(Unit)
        store.commit(
            UsersChanges(
                blocks = listOf(Block(userId, target, clock.instant())),
                events = listOf(bus.envelope(UserBlocked(userId, target))),
            ),
        )
        cache.invalidateTarget(target)
        cache.invalidateTarget(userId)
        return Resource.success(Unit)
    }
}
