package com.circaso.users.domain.usecases

import com.circaso.shared.EventBus
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.envelope
import com.circaso.users.api.ProfileUpdated
import com.circaso.users.domain.errors.UsersError
import com.circaso.users.domain.models.MyProfile
import com.circaso.users.domain.models.ProfileChange
import com.circaso.users.domain.repositories.ProfileRepository
import com.circaso.users.domain.repositories.UsersChanges
import com.circaso.users.domain.repositories.UsersStore
import com.circaso.users.domain.support.HANDLE_COOLDOWN
import com.circaso.users.domain.support.MAX_ABOUT
import com.circaso.users.domain.support.MAX_DISPLAY_NAME
import com.circaso.users.domain.support.ProfileProvisioner
import com.circaso.users.domain.support.ProfileViews
import com.circaso.users.domain.support.UserNotifier

/**
 * U2: name, about and privacy, with an optimistic lock on `version`: a stale version is `users.version_conflict`.
 *
 * TODO(decision: the ProfileUpdated event is written after the version-checked update, not in the same batch,
 * because a lightweight transaction can't join one; a crash between the two loses the event)
 */
class UpdateProfile(
    private val provisioner: ProfileProvisioner,
    private val profiles: ProfileRepository,
    private val store: UsersStore,
    private val bus: EventBus,
    private val notifier: UserNotifier,
    private val views: ProfileViews,
) {
    suspend operator fun invoke(userId: UserId, change: ProfileChange): Resource<MyProfile> {
        val old = provisioner.ensure(userId)
        if (change.version != old.version) return Resource.error(UsersError.VersionConflict)

        val name = change.displayName?.trim()
        if (name != null && (name.isEmpty() || name.length > MAX_DISPLAY_NAME)) {
            return Resource.error(UsersError.ProfileInvalid("displayName", "must be 1 to $MAX_DISPLAY_NAME characters"))
        }
        val about = change.about?.trim()
        if (about != null &&
            about.length > MAX_ABOUT
        ) {
            return Resource.error(UsersError.ProfileInvalid("about", "must be at most $MAX_ABOUT characters"))
        }

        val new =
            old.copy(
                displayName = name ?: old.displayName,
                about =
                    when {
                        change.clearAbout -> null
                        about != null -> about.ifEmpty { null }
                        else -> old.about
                    },
                privacy =
                    old.privacy.copy(
                        avatar = change.avatarVisibility ?: old.privacy.avatar,
                        about = change.aboutVisibility ?: old.privacy.about,
                        handleLookup = change.handleLookup ?: old.privacy.handleLookup,
                    ),
            )
        val saved = profiles.update(old, new) ?: return Resource.error(UsersError.VersionConflict)

        val changed =
            buildList {
                if (new.displayName != old.displayName) add(ProfileUpdated.DISPLAY_NAME)
                if (new.about != old.about) add(ProfileUpdated.ABOUT)
            }
        if (changed.isNotEmpty()) {
            store.commit(UsersChanges(events = listOf(bus.envelope(ProfileUpdated(userId, saved.version, changed)))))
            notifier.profileUpdated(userId, saved.version)
        }
        return Resource.success(MyProfile(saved, views.avatarUrl(saved), saved.handleChangedAt?.plus(HANDLE_COOLDOWN)))
    }
}
