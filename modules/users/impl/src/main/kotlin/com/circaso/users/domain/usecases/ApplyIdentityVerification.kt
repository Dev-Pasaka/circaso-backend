package com.circaso.users.domain.usecases

import com.circaso.identity.api.IdentityVerificationChanged
import com.circaso.shared.EventBus
import com.circaso.shared.Resource
import com.circaso.shared.envelope
import com.circaso.users.api.ProfileUpdated
import com.circaso.users.domain.ports.Transactor
import com.circaso.users.domain.repositories.ProfileRepository
import com.circaso.users.domain.repositories.UsersChanges
import com.circaso.users.domain.repositories.UsersStore
import com.circaso.users.domain.support.ProfileProvisioner
import com.circaso.users.domain.support.UserNotifier
import java.time.Instant

/**
 * `identity.IdentityVerificationChanged` (the verified badge, 2026-11-24): the profile shows the badge from now on, or
 * stops showing it. The profile moves one version on, `users.ProfileUpdated` (`changed: ["verified"]`) is published,
 * and `users/profile.updated` goes to the person and to everyone who has them saved, so badges change live.
 * [occurredAt] is the event's time: a late, older event changes nothing, so retries and reordering are safe.
 */
class ApplyIdentityVerification(
    private val provisioner: ProfileProvisioner,
    private val profiles: ProfileRepository,
    private val transactor: Transactor,
    private val store: UsersStore,
    private val bus: EventBus,
    private val notifier: UserNotifier,
) {
    suspend operator fun invoke(event: IdentityVerificationChanged, occurredAt: Instant): Resource<Unit> {
        // A verification that arrives before the profile exists still has to show once it does.
        provisioner.ensure(event.userId)
        val verifiedAt = if (event.verified) event.verifiedAt ?: occurredAt else null
        val (version, envelope) =
            transactor.run("profiles.verification") {
                val version = profiles.recordVerification(event.userId, verifiedAt, occurredAt) ?: return@run null
                val envelope =
                    bus.envelope(ProfileUpdated(event.userId, version, listOf(ProfileUpdated.VERIFIED)))
                store.commit(UsersChanges(events = listOf(envelope)))
                version to envelope
            } ?: return Resource.success(Unit)
        bus.publish(envelope)
        notifier.verificationChanged(event.userId, version)
        return Resource.success(Unit)
    }
}
