package com.circaso.identity.domain.usecases

import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.support.IdentityConfig
import com.circaso.shared.Resource
import java.time.Clock
import java.time.Instant

/**
 * The scheduled sweep of sign-ups that never finished (identity.md → Sign-in decision table): an account still
 * `PENDING_PHONE` [IdentityConfig.pendingAccountLifetime] after it began is deleted, with its logins and log, which
 * frees its number and its Google/Apple login. Such an account was never activated, so no other module knows it and
 * nothing is published. Deletes in chunks, each its own transaction; returns how many accounts went.
 */
class SweepPendingAccounts(
    private val accounts: AccountRepository,
    private val config: IdentityConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(): Resource<Int> = startedBefore(clock.instant().minus(config.pendingAccountLifetime))

    /** Deletes every unfinished sign-up started before [cutoff]; the 30-day sweep and staff's clean-up both use it. */
    suspend fun startedBefore(cutoff: Instant): Resource<Int> {
        var total = 0
        while (true) {
            val deleted = accounts.deleteAbandoned(cutoff, CHUNK)
            total += deleted
            if (deleted < CHUNK) break
        }
        return Resource.success(total)
    }

    private companion object {
        const val CHUNK = 500
    }
}
