package com.circaso.identity.domain.support

import com.circaso.identity.domain.repositories.AccountRepository
import java.time.Instant

/**
 * Deletes every unfinished sign-up (`PENDING_PHONE`) started before a cutoff, in chunks, each its own transaction:
 * the 30-day sweep and staff's clean-up (2026-11-13) share it. Returns how many accounts went.
 */
object AbandonedSignUps {
    private const val CHUNK = 500

    suspend fun deleteStartedBefore(accounts: AccountRepository, cutoff: Instant): Int {
        var total = 0
        while (true) {
            val deleted = accounts.deleteAbandoned(cutoff, CHUNK)
            total += deleted
            if (deleted < CHUNK) return total
        }
    }
}
