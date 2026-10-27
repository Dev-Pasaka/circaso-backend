package com.circaso.users.domain.usecases

import com.circaso.shared.EventBus
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.envelope
import com.circaso.users.api.UserUnblocked
import com.circaso.users.domain.ports.ReachCache
import com.circaso.users.domain.ports.Transactor
import com.circaso.users.domain.repositories.BlockRepository
import com.circaso.users.domain.repositories.UsersChanges
import com.circaso.users.domain.repositories.UsersStore

/** U14: lifts a block. The contact, if there was one, is exactly as it was. */
class UnblockUser(
    private val blocks: BlockRepository,
    private val transactor: Transactor,
    private val store: UsersStore,
    private val bus: EventBus,
    private val cache: ReachCache,
) {
    suspend operator fun invoke(userId: UserId, target: UserId): Resource<Unit> {
        val event = bus.envelope(UserUnblocked(userId, target))
        val unblocked =
            transactor.run("blocks.remove") {
                blocks.remove(userId, target).also { removed ->
                    if (removed) store.commit(UsersChanges(events = listOf(event)))
                }
            }
        if (!unblocked) return Resource.success(Unit)
        bus.publish(event)
        cache.invalidateTarget(target)
        cache.invalidateTarget(userId)
        return Resource.success(Unit)
    }
}
