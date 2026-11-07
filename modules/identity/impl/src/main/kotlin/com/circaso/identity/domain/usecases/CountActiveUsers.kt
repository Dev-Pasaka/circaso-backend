package com.circaso.identity.domain.usecases

import com.circaso.identity.api.ActiveCounts
import com.circaso.identity.domain.repositories.AccountAdminRepository
import com.circaso.shared.Resource
import java.time.Duration
import java.time.Instant

/**
 * Staff: distinct people whose phone was last seen within a day, a week and 30 days of [now] (decision 2026-11-08).
 * "Seen" is the active phone's `last_seen_at`, which registering and refreshing a session move (I6, I7), so a person
 * who opened the app counts. Only people have devices (a support account never registers one), so the count is of
 * people without a join.
 */
class CountActiveUsers(private val admin: AccountAdminRepository) {
    suspend operator fun invoke(now: Instant): Resource<ActiveCounts> =
        Resource.success(
            ActiveCounts(
                day1 = admin.activeSince(now.minus(Duration.ofDays(1))),
                day7 = admin.activeSince(now.minus(Duration.ofDays(7))),
                day30 = admin.activeSince(now.minus(Duration.ofDays(30))),
            ),
        )
}
