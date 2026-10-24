package com.circaso.users.domain.usecases

import com.circaso.shared.EventBus
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.envelope
import com.circaso.users.api.ProfileUpdated
import com.circaso.users.domain.errors.UsersError
import com.circaso.users.domain.models.HandleClaim
import com.circaso.users.domain.models.MyProfile
import com.circaso.users.domain.repositories.HandleRepository
import com.circaso.users.domain.repositories.ProfileRepository
import com.circaso.users.domain.repositories.UsersChanges
import com.circaso.users.domain.repositories.UsersStore
import com.circaso.users.domain.support.HANDLE_COOLDOWN
import com.circaso.users.domain.support.HandleRules
import com.circaso.users.domain.support.ProfileProvisioner
import com.circaso.users.domain.support.ProfileViews
import com.circaso.users.domain.support.UserNotifier
import java.time.Clock

/**
 * U6: claims or changes the @handle, once every 30 days. The claim is a lightweight transaction on its own table;
 * the old handle goes on a 30-day hold for its owner, so nobody can take it the moment it is released.
 */
class ClaimHandle(
    private val provisioner: ProfileProvisioner,
    private val profiles: ProfileRepository,
    private val handles: HandleRepository,
    private val store: UsersStore,
    private val bus: EventBus,
    private val notifier: UserNotifier,
    private val views: ProfileViews,
    private val clock: Clock,
) {
    suspend operator fun invoke(userId: UserId, raw: String): Resource<MyProfile> {
        val handle = HandleRules.normalise(raw) ?: return Resource.error(UsersError.HandleInvalid)
        val old = provisioner.ensure(userId)
        val changeable = old.handleChangedAt?.plus(HANDLE_COOLDOWN)
        if (old.handle == handle) return Resource.success(MyProfile(old, views.avatarUrl(old), changeable))
        val now = clock.instant()
        if (changeable != null && now.isBefore(changeable)) return Resource.error(UsersError.HandleCooldown)

        if (!handles.claim(handle, userId)) return Resource.error(UsersError.HandleTaken)
        val saved =
            profiles.update(old, old.copy(handle = handle, handleChangedAt = now))
                ?: return Resource.error(UsersError.VersionConflict)

        store.commit(
            UsersChanges(
                releasedHandles =
                    listOfNotNull(
                        old.handle?.let {
                            HandleClaim(
                                it,
                                userId,
                                old.handleChangedAt ?: old.createdAt,
                                now,
                            )
                        },
                    ),
                events = listOf(bus.envelope(ProfileUpdated(userId, saved.version, listOf(ProfileUpdated.HANDLE)))),
            ),
        )
        notifier.profileUpdated(userId, saved.version)
        return Resource.success(MyProfile(saved, views.avatarUrl(saved), now.plus(HANDLE_COOLDOWN)))
    }
}
