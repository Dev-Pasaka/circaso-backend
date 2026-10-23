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
import com.circaso.users.domain.support.UsersConfig

/** U4: confirms an uploaded image. The object must be the caller's own, exist, and be a picture of an allowed type and size. */
class SetAvatar(
    private val provisioner: ProfileProvisioner,
    private val profiles: ProfileRepository,
    private val storage: AvatarStorage,
    private val store: UsersStore,
    private val bus: EventBus,
    private val notifier: UserNotifier,
    private val views: ProfileViews,
    private val config: UsersConfig,
) {
    suspend operator fun invoke(userId: UserId, objectKey: String): Resource<MyProfile> {
        if (!objectKey.startsWith("avatars/$userId/")) return Resource.error(UsersError.AvatarInvalid)
        val stored = storage.describe(objectKey) ?: return Resource.error(UsersError.AvatarInvalid)
        if (stored.contentType !in AVATAR_TYPES || stored.sizeBytes > config.maxAvatarBytes) return Resource.error(UsersError.AvatarInvalid)

        val old = provisioner.ensure(userId)
        val saved = profiles.update(old, old.copy(avatarKey = objectKey)) ?: return Resource.error(UsersError.VersionConflict)
        old.avatarKey?.takeIf { it != objectKey }?.let { storage.delete(it) }
        store.commit(UsersChanges(events = listOf(bus.envelope(ProfileUpdated(userId, saved.version, listOf(ProfileUpdated.AVATAR))))))
        notifier.profileUpdated(userId, saved.version)
        return Resource.success(MyProfile(saved, views.avatarUrl(saved), saved.handleChangedAt?.plus(HANDLE_COOLDOWN)))
    }
}
