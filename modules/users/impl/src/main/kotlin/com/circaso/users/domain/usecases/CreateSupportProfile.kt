package com.circaso.users.domain.usecases

import com.circaso.identity.api.SupportAccountCreated
import com.circaso.shared.Resource
import com.circaso.users.domain.errors.UsersError
import com.circaso.users.domain.models.Privacy
import com.circaso.users.domain.models.Profile
import com.circaso.users.domain.ports.Transactor
import com.circaso.users.domain.repositories.HandleRepository
import com.circaso.users.domain.repositories.ProfileRepository
import com.circaso.users.domain.support.DEFAULT_DISPLAY_NAME
import com.circaso.users.domain.support.HandleRules
import com.circaso.users.domain.support.MAX_DISPLAY_NAME
import java.time.Clock

/**
 * `identity.SupportAccountCreated` (decision 2026-10-31): the support account's profile, marked `support`, with the
 * handle ops chose. The claim and the profile are one transaction. A handle someone took since ops checked it is left
 * off rather than failing the event for ever; the ops list then shows the account without one. Safe to run twice: a
 * profile already marked `support` is left as it is, and one made early by a first read is marked and named. A
 * profile written by someone else meanwhile rolls the claim back and fails, so the event is retried.
 */
class CreateSupportProfile(
    private val profiles: ProfileRepository,
    private val handles: HandleRepository,
    private val transactor: Transactor,
    private val clock: Clock,
) {
    suspend operator fun invoke(event: SupportAccountCreated): Resource<Unit> {
        val existing = profiles.find(event.userId)
        if (existing?.support == true) return Resource.success(Unit)

        val now = clock.instant()
        val handle = HandleRules.normalise(event.handle, allowReserved = true)
        val name =
            event.displayName
                .trim()
                .take(MAX_DISPLAY_NAME)
                .ifEmpty { DEFAULT_DISPLAY_NAME }
        return transactor.attempt("profiles.create-support") {
            val claimed = handle?.takeIf { handles.claim(it, event.userId, now) }
            val made =
                Profile(
                    userId = event.userId,
                    displayName = name,
                    about = null,
                    handle = claimed,
                    handleChangedAt = claimed?.let { now },
                    avatarKey = null,
                    privacy = Privacy(),
                    createdAt = now,
                    support = true,
                )
            val saved =
                if (existing == null) {
                    profiles.create(made)
                } else {
                    profiles.update(existing, marked(existing, made)) != null
                }
            if (saved) Resource.success(Unit) else changedMeanwhile()
        }
    }

    /** A profile a first read made before the event: keeps its own handle if this one was lost. */
    private fun marked(existing: Profile, made: Profile) =
        existing.copy(
            displayName = made.displayName,
            handle = made.handle ?: existing.handle,
            handleChangedAt = made.handleChangedAt ?: existing.handleChangedAt,
            support = true,
        )

    /** Rolls the claim back; the event is retried and reads the profile again. */
    private fun changedMeanwhile(): Resource<Unit> = Resource.error(UsersError.VersionConflict)
}
