package com.circaso.users.domain.usecases

import com.circaso.identity.api.AccountDirectory
import com.circaso.shared.Resource
import com.circaso.users.domain.errors.UsersError
import com.circaso.users.domain.models.Contact
import com.circaso.users.domain.models.ContactSource
import com.circaso.users.domain.models.ContactStatus
import com.circaso.users.domain.models.SyncCommand
import com.circaso.users.domain.models.SyncMatch
import com.circaso.users.domain.models.WaitingContact
import com.circaso.users.domain.ports.PhoneHasher
import com.circaso.users.domain.ports.ReachCache
import com.circaso.users.domain.ports.UsersCache
import com.circaso.users.domain.repositories.ContactRepository
import com.circaso.users.domain.repositories.ProfileRepository
import com.circaso.users.domain.repositories.UsersChanges
import com.circaso.users.domain.repositories.UsersStore
import com.circaso.users.domain.support.ProfileViews
import com.circaso.users.domain.support.fallbackProfile
import java.time.Clock

/**
 * U7: a batch of phone-book numbers. Numbers that belong to active accounts become contacts (unless the user
 * removed that person) and come back with public profiles; the rest are kept only as keyed hashes, so the user
 * hears when they join. Names from the phone book never reach the server.
 */
class SyncContacts(
    private val directory: AccountDirectory,
    private val contacts: ContactRepository,
    private val profiles: ProfileRepository,
    private val views: ProfileViews,
    private val hasher: PhoneHasher,
    private val store: UsersStore,
    private val cache: ReachCache,
    private val usersCache: UsersCache,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: SyncCommand): Resource<List<SyncMatch>> {
        if (command.phones.size > MAX_BATCH) return Resource.error(UsersError.SyncTooLarge)
        val owner = command.userId
        val now = clock.instant()

        val resolved = directory.resolvePhones(command.phones).filterValues { it != owner }
        val existing = resolved.values.toSet().associateWith { contacts.find(owner, it) }

        val added =
            existing
                .filterValues { it == null }
                .keys
                .map { Contact(owner, it, ContactSource.PHONEBOOK, ContactStatus.ACTIVE, null, now) }
        val waiting = (command.phones - resolved.keys).map { WaitingContact(hasher.hmac(it), owner, now) }
        store.commit(UsersChanges(contacts = added, waiting = waiting))
        added.forEach { cache.invalidateTarget(it.contactId) }
        usersCache.recordSyncBatch(owner, command.syncId, command.final)

        val shown = resolved.filter { (_, user) -> existing[user]?.status != ContactStatus.REMOVED }
        val found = profiles.findAll(shown.values.toSet())
        return Resource.success(
            shown.map { (phone, user) ->
                SyncMatch(
                    phone,
                    found[user]?.let { views.forViewer(owner, it) } ?: fallbackProfile(user),
                )
            },
        )
    }

    private companion object {
        const val MAX_BATCH = 1_000
    }
}
