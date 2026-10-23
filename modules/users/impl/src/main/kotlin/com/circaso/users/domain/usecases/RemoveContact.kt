package com.circaso.users.domain.usecases

import com.circaso.shared.EventBus
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.envelope
import com.circaso.users.api.ContactRemoved
import com.circaso.users.domain.errors.UsersError
import com.circaso.users.domain.models.ContactStatus
import com.circaso.users.domain.ports.ReachCache
import com.circaso.users.domain.repositories.ContactRepository
import com.circaso.users.domain.repositories.UsersChanges
import com.circaso.users.domain.repositories.UsersStore
import java.time.Clock

/**
 * U16: removes someone from the user's contacts. The row stays, marked removed, so the next sync doesn't add the
 * person back while their number is still in the phone book; the reverse row goes. The other person isn't told.
 */
class RemoveContact(
    private val contacts: ContactRepository,
    private val store: UsersStore,
    private val bus: EventBus,
    private val cache: ReachCache,
    private val clock: Clock,
) {
    suspend operator fun invoke(userId: UserId, contactId: UserId): Resource<Unit> {
        val contact = contacts.find(userId, contactId)?.takeIf { it.status == ContactStatus.ACTIVE } ?: return Resource.error(UsersError.ContactNotFound)
        store.commit(
            UsersChanges(
                contacts = listOf(contact.copy(status = ContactStatus.REMOVED, removedAt = clock.instant())),
                events = listOf(bus.envelope(ContactRemoved(userId, contactId))),
            ),
        )
        cache.invalidateTarget(contactId)
        return Resource.success(Unit)
    }
}
