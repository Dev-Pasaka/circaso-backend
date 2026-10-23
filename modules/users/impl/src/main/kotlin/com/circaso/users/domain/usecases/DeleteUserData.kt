package com.circaso.users.domain.usecases

import com.circaso.identity.api.AccountDeleted
import com.circaso.shared.Resource
import com.circaso.users.domain.models.HandleClaim
import com.circaso.users.domain.ports.AvatarStorage
import com.circaso.users.domain.ports.ReachCache
import com.circaso.users.domain.repositories.BlockRepository
import com.circaso.users.domain.repositories.ContactRepository
import com.circaso.users.domain.repositories.ProfileRepository
import com.circaso.users.domain.repositories.UsersChanges
import com.circaso.users.domain.repositories.UsersStore
import java.time.Clock

/**
 * `identity.AccountDeleted`: deletes the profile, the contacts in both directions and the blocks, and puts the
 * handle on its 30-day hold. Numbers the user was waiting for expire on their own after 180 days, since that table
 * is keyed by number, not by owner.
 */
class DeleteUserData(
    private val profiles: ProfileRepository,
    private val contacts: ContactRepository,
    private val blocks: BlockRepository,
    private val storage: AvatarStorage,
    private val store: UsersStore,
    private val cache: ReachCache,
    private val clock: Clock,
) {
    suspend operator fun invoke(event: AccountDeleted): Resource<Unit> {
        val userId = event.userId
        val profile = profiles.find(userId)
        val mine = contacts.list(userId, null, EVERYTHING)
        val theirs = contacts.ownersOf(userId, EVERYTHING)
        val blocked = blocks.list(userId)
        val blockedBy = blocks.blockedBy(userId)

        store.commit(
            UsersChanges(
                deletedProfiles = listOfNotNull(profile?.userId),
                deletedContacts = mine.map { userId to it.contactId } + theirs.map { it to userId },
                unblocks = blocked + blockedBy,
                releasedHandles = listOfNotNull(profile?.handle?.let { HandleClaim(it, userId, profile.handleChangedAt ?: profile.createdAt, clock.instant()) }),
            ),
        )
        profile?.avatarKey?.let { storage.delete(it) }
        cache.invalidateTarget(userId)
        return Resource.success(Unit)
    }

    private companion object {
        const val EVERYTHING = 10_000
    }
}
