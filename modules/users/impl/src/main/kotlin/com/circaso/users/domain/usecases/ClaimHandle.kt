package com.circaso.users.domain.usecases

import com.circaso.shared.EventBus
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.shared.envelope
import com.circaso.users.api.ProfileUpdated
import com.circaso.users.domain.errors.UsersError
import com.circaso.users.domain.models.HandleClaim
import com.circaso.users.domain.models.MyProfile
import com.circaso.users.domain.ports.Transactor
import com.circaso.users.domain.ports.UsersCache
import com.circaso.users.domain.repositories.HandleRepository
import com.circaso.users.domain.repositories.ProfileRepository
import com.circaso.users.domain.repositories.UsersChanges
import com.circaso.users.domain.repositories.UsersStore
import com.circaso.users.domain.support.HANDLE_COOLDOWN
import com.circaso.users.domain.support.HandleRules
import com.circaso.users.domain.support.MAX_HANDLE_CHANGES_PER_DAY
import com.circaso.users.domain.support.ProfileProvisioner
import com.circaso.users.domain.support.ProfileViews
import com.circaso.users.domain.support.UserNotifier
import java.time.Clock

/**
 * U6: claims or changes the @handle, whenever the new one is free (at most [MAX_HANDLE_CHANGES_PER_DAY] a day). The claim, the profile's version-checked update, the old
 * handle's 30-day hold and the outbox row are one transaction: a claim that loses either race leaves nothing behind.
 */
class ClaimHandle(
    private val provisioner: ProfileProvisioner,
    private val profiles: ProfileRepository,
    private val handles: HandleRepository,
    private val transactor: Transactor,
    private val store: UsersStore,
    private val bus: EventBus,
    private val notifier: UserNotifier,
    private val views: ProfileViews,
    private val clock: Clock,
    private val usersCache: UsersCache,
) {
    suspend operator fun invoke(userId: UserId, raw: String): Resource<MyProfile> {
        val handle = HandleRules.normalise(raw) ?: return Resource.error(UsersError.HandleInvalid)
        val old = provisioner.ensure(userId)
        val changeable = old.handleChangedAt?.plus(HANDLE_COOLDOWN)
        if (old.handle == handle) return Resource.success(MyProfile(old, views.avatarUrl(old), changeable))
        val now = clock.instant()
        if (changeable != null && now.isBefore(changeable)) return Resource.error(UsersError.HandleCooldown)
        if (old.handle != null && usersCache.countHandleChange(userId) > MAX_HANDLE_CHANGES_PER_DAY) {
            return Resource.error(SharedError.RateLimited(usersCache.handleChangeRetryAfter(userId)?.seconds ?: 86_400))
        }

        val released = old.handle?.let { HandleClaim(it, userId, old.handleChangedAt ?: old.createdAt, now) }
        val event = bus.envelope(ProfileUpdated(userId, old.version + 1, listOf(ProfileUpdated.HANDLE)))
        val claimed =
            transactor.attempt("handles.claim") {
                if (!handles.claim(handle, userId, now)) return@attempt Resource.error(UsersError.HandleTaken)
                val updated =
                    profiles.update(old, old.copy(handle = handle, handleChangedAt = now))
                        ?: return@attempt Resource.error(UsersError.VersionConflict)
                store.commit(UsersChanges(releasedHandles = listOfNotNull(released), events = listOf(event)))
                Resource.success(updated)
            }
        val saved =
            when (claimed) {
                is Resource.Success -> claimed.data
                is Resource.Error -> return alreadyHeld(userId, handle, claimed)
            }

        bus.publish(event)
        notifier.profileUpdated(userId, saved.version)
        return Resource.success(MyProfile(saved, views.avatarUrl(saved), now.plus(HANDLE_COOLDOWN)))
    }

    /**
     * A lost version race where the profile now has this very handle is the same user's parallel claim winning, so
     * it answers like a repeat of the claim; any other failure stands.
     */
    private suspend fun alreadyHeld(
        userId: UserId,
        handle: String,
        failure: Resource.Error,
    ): Resource<MyProfile> {
        if (failure.error != UsersError.VersionConflict) return failure
        val current = profiles.find(userId)?.takeIf { it.handle == handle } ?: return failure
        val changeable = current.handleChangedAt?.plus(HANDLE_COOLDOWN)
        return Resource.success(MyProfile(current, views.avatarUrl(current), changeable))
    }
}
