package com.circaso.users.domain.usecases

import com.circaso.identity.api.AccountDirectory
import com.circaso.shared.EventBus
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.shared.envelope
import com.circaso.users.api.UserBlocked
import com.circaso.users.domain.errors.UsersError
import com.circaso.users.domain.models.Block
import com.circaso.users.domain.ports.ReachCache
import com.circaso.users.domain.ports.Transactor
import com.circaso.users.domain.repositories.BlockRepository
import com.circaso.users.domain.repositories.UsersChanges
import com.circaso.users.domain.repositories.UsersStore
import java.time.Clock

/**
 * U14: blocks someone. A block overrides every other way of reaching a person, in both directions. Blocking an id
 * that is no account answers `request.not_found`.
 */
class BlockUser(
    private val directory: AccountDirectory,
    private val blocks: BlockRepository,
    private val transactor: Transactor,
    private val store: UsersStore,
    private val bus: EventBus,
    private val cache: ReachCache,
    private val clock: Clock,
) {
    suspend operator fun invoke(userId: UserId, target: UserId): Resource<Unit> {
        if (userId == target) return Resource.error(UsersError.CannotBlockSelf)
        // An id that belongs to no account is the spec's unknown target: nothing is written.
        if (directory.status(target) == null) return Resource.error(SharedError.NotFound)
        val event = bus.envelope(UserBlocked(userId, target))
        val blocked =
            transactor.run("blocks.add") {
                blocks.add(Block(userId, target, clock.instant())).also { added ->
                    if (added) store.commit(UsersChanges(events = listOf(event)))
                }
            }
        if (!blocked) return Resource.success(Unit)
        bus.publish(event)
        cache.invalidateTarget(target)
        cache.invalidateTarget(userId)
        return Resource.success(Unit)
    }
}
