package com.circaso.users.domain.usecases

import com.circaso.identity.api.AccountDeleted
import com.circaso.shared.Resource
import com.circaso.users.domain.models.HandleClaim
import com.circaso.users.domain.ports.AvatarStorage
import com.circaso.users.domain.ports.ReachCache
import com.circaso.users.domain.ports.Transactor
import com.circaso.users.domain.repositories.ProfileRepository
import com.circaso.users.domain.repositories.UsersChanges
import com.circaso.users.domain.repositories.UsersStore
import java.time.Clock

/**
 * `identity.AccountDeleted`: deletes the profile, the contacts in both directions, the blocks either way and the
 * user's invites, and puts the handle on its 30-day hold, in one transaction. Numbers the user was waiting for expire on their own after
 * 180 days, since that table is keyed by number, not by owner.
 */
class DeleteUserData(
    private val profiles: ProfileRepository,
    private val storage: AvatarStorage,
    private val transactor: Transactor,
    private val store: UsersStore,
    private val cache: ReachCache,
    private val clock: Clock,
) {
    suspend operator fun invoke(event: AccountDeleted): Resource<Unit> {
        val userId = event.userId
        val now = clock.instant()
        val profile =
            transactor.run("users.erase") {
                val profile = profiles.find(userId)
                val handle =
                    profile?.handle?.let { HandleClaim(it, userId, profile.handleChangedAt ?: profile.createdAt, now) }
                store.commit(UsersChanges(erasedUsers = listOf(userId), releasedHandles = listOfNotNull(handle)))
                profile
            }
        profile?.avatarKey?.let { storage.delete(it) }
        cache.invalidateTarget(userId)
        return Resource.success(Unit)
    }
}
