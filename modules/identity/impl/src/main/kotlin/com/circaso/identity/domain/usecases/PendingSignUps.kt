package com.circaso.identity.domain.usecases

import com.circaso.identity.domain.repositories.AccountAdminRepository
import com.circaso.shared.Resource
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Staff clean-up of unfinished sign-ups (2026-11-13): accounts that signed in with Google or Apple and never verified a
 * phone (`PENDING_PHONE`). Most are Google Play's pre-launch robots. The 30-day sweep removes them anyway; this lets an
 * admin do it now. Sign-ups started within [GRACE] are left alone, so a person typing their code right now isn't cut
 * off half way.
 */
object PendingSignUpRules {
    val GRACE: Duration = Duration.ofHours(1)

    fun cutoff(clock: Clock): Instant = clock.instant().minus(GRACE)
}

/** How many unfinished sign-ups a clean-up would delete now. */
class CountPendingSignUps(private val admin: AccountAdminRepository, private val clock: Clock) {
    suspend operator fun invoke(): Resource<Long> =
        Resource.success(admin.pendingCount(PendingSignUpRules.cutoff(clock)))
}

/** Deletes them, with their logins and log, as the sweep does; returns how many went. */
class DeletePendingSignUps(private val sweep: SweepPendingAccounts, private val clock: Clock) {
    suspend operator fun invoke(): Resource<Int> = sweep.startedBefore(PendingSignUpRules.cutoff(clock))
}
