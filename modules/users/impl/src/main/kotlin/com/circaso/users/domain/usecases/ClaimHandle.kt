package com.circaso.users.domain.usecases

import com.circaso.shared.EventBus
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.envelope
import com.circaso.users.api.ProfileUpdated
import com.circaso.users.domain.errors.UsersError
import com.circaso.users.domain.models.HandleClaim
import com.circaso.users.domain.models.MyProfile
import com.circaso.users.domain.ports.Transactor
import com.circaso.users.domain.repositories.HandleRepository
import com.circaso.users.domain.repositories.ProfileRepository
import com.circaso.users.domain.repositories.UsersChanges
import com.circaso.users.domain.repositories.UsersStore
import com.circaso.users.domain.support.HANDLE_COOLDOWN
import com.circaso.users.domain.support.HandleRules
import com.circaso.users.domain.support.ProfileProvisioner
import com.circaso.users.domain.support.ProfileViews
import com.circaso.users.domain.support.Rollback
import com.circaso.users.domain.support.UserNotifier
import java.time.Clock

/**
 * U6: claims or changes the @handle, once every 30 days. The claim, the profile's version-checked update, the old
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
) {
    suspend operator fun invoke(userId: UserId, raw: String): Resource<MyProfile> {
        val handle = HandleRules.normalise(raw) ?: return Resource.error(UsersError.HandleInvalid)
        val old = provisioner.ensure(userId)
        val changeable = old.handleChangedAt?.plus(HANDLE_COOLDOWN)
        if (old.handle == handle) return Resource.success(MyProfile(old, views.avatarUrl(old), changeable))
        val now = clock.instant()
        if (changeable != null && now.isBefore(changeable)) return Resource.error(UsersError.HandleCooldown)

        val released = old.handle?.let { HandleClaim(it, userId, old.handleChangedAt ?: old.createdAt, now) }
        val event = bus.envelope(ProfileUpdated(userId, old.version + 1, listOf(ProfileUpdated.HANDLE)))
        val saved =
            try {
                transactor.run("handles.claim") {
                    if (!handles.claim(handle, userId, now)) throw Rollback(UsersError.HandleTaken)
                    val updated =
                        profiles.update(old, old.copy(handle = handle, handleChangedAt = now))
                            ?: throw Rollback(UsersError.VersionConflict)
                    store.commit(UsersChanges(releasedHandles = listOfNotNull(released), events = listOf(event)))
                    updated
                }
            } catch (e: Rollback) {
                return Resource.error(e.error)
            }

        bus.publish(event)
        notifier.profileUpdated(userId, saved.version)
        return Resource.success(MyProfile(saved, views.avatarUrl(saved), now.plus(HANDLE_COOLDOWN)))
    }
}
