package com.circaso.users.domain.usecases

import com.circaso.shared.EventBus
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.envelope
import com.circaso.users.api.UserUnblocked
import com.circaso.users.domain.ports.ReachCache
import com.circaso.users.domain.ports.Transactor
import com.circaso.users.domain.models.ContactStatus
import com.circaso.users.domain.repositories.BlockRepository
import com.circaso.users.domain.repositories.ContactRepository
import com.circaso.users.domain.repositories.UsersChanges
import com.circaso.users.domain.repositories.UsersStore

/**
 * U14: lifts a block, and gives back the contact the block took (2026-11-10). Blocking from the app used to remove
 * the contact too, which left an unblocked person unreachable (no messages, missing from contacts and search) with no
 * way back. So a removed contact for the unblocked person is made active again with its first source and date; one
 * that's active, or never existed, is left as it is.
 */
class UnblockUser(
    private val blocks: BlockRepository,
    private val transactor: Transactor,
    private val store: UsersStore,
    private val bus: EventBus,
    private val cache: ReachCache,
    private val contacts: ContactRepository,
) {
    suspend operator fun invoke(userId: UserId, target: UserId): Resource<Unit> {
        val event = bus.envelope(UserUnblocked(userId, target))
        val unblocked =
            transactor.run("blocks.remove") {
                blocks.remove(userId, target).also { removed ->
                    if (removed) {
                        contacts
                            .find(userId, target)
                            ?.takeIf { it.status == ContactStatus.REMOVED }
                            ?.let { contacts.connect(it.copy(status = ContactStatus.ACTIVE, removedAt = null)) }
                        store.commit(UsersChanges(events = listOf(event)))
                    }
                }
            }
        if (!unblocked) return Resource.success(Unit)
        bus.publish(event)
        cache.invalidateTarget(target)
        cache.invalidateTarget(userId)
        return Resource.success(Unit)
    }
}
