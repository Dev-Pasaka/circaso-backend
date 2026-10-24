package com.circaso.users.domain.usecases

import com.circaso.shared.EventBus
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.envelope
import com.circaso.users.api.ProfileUpdated
import com.circaso.users.domain.errors.UsersError
import com.circaso.users.domain.models.MyProfile
import com.circaso.users.domain.ports.AvatarStorage
import com.circaso.users.domain.repositories.ProfileRepository
import com.circaso.users.domain.repositories.UsersChanges
import com.circaso.users.domain.repositories.UsersStore
import com.circaso.users.domain.support.HANDLE_COOLDOWN
import com.circaso.users.domain.support.ProfileProvisioner
import com.circaso.users.domain.support.ProfileViews
import com.circaso.users.domain.support.UserNotifier

/** U4: removes the profile picture. */
class RemoveAvatar(
    private val provisioner: ProfileProvisioner,
    private val profiles: ProfileRepository,
    private val storage: AvatarStorage,
    private val store: UsersStore,
    private val bus: EventBus,
    private val notifier: UserNotifier,
    private val views: ProfileViews,
) {
    suspend operator fun invoke(userId: UserId): Resource<MyProfile> {
        val old = provisioner.ensure(userId)
        val key =
            old.avatarKey ?: return Resource.success(MyProfile(old, null, old.handleChangedAt?.plus(HANDLE_COOLDOWN)))
        val saved =
            profiles.update(old, old.copy(avatarKey = null)) ?: return Resource.error(UsersError.VersionConflict)
        storage.delete(key)
        store.commit(
            UsersChanges(
                events = listOf(bus.envelope(ProfileUpdated(userId, saved.version, listOf(ProfileUpdated.AVATAR)))),
            ),
        )
        notifier.profileUpdated(userId, saved.version)
        return Resource.success(MyProfile(saved, views.avatarUrl(saved), saved.handleChangedAt?.plus(HANDLE_COOLDOWN)))
    }
}
