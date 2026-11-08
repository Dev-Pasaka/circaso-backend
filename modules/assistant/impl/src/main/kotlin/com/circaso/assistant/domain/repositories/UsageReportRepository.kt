package com.circaso.assistant.domain.repositories

import com.circaso.assistant.api.UsageRates
import com.circaso.assistant.domain.models.DayActivity
import com.circaso.assistant.domain.models.TokenCounts
import com.circaso.assistant.domain.models.UserActivity
import com.circaso.shared.UserId
import java.time.LocalDate

/**
 * Staff reads over `usage_daily` and `usage_daily_tokens` (decision 2026-11-08). Every range is `[from, until)` in UTC
 * days and served by the `usage_day` indexes of V4; callers bound it (366 days at most).
 */
interface UsageReportRepository {
    /** Each day of the range that has any usage row, oldest first. */
    suspend fun activityByDay(from: LocalDate, until: LocalDate): List<DayActivity>

    /** Each day's tokens by model. */
    suspend fun tokensByDay(from: LocalDate, until: LocalDate): Map<LocalDate, Map<String, TokenCounts>>

    /** How many different users have a usage row in the range. */
    suspend fun users(from: LocalDate, until: LocalDate): Long

    /** The [limit] users whose tokens and web searches over the range weigh most at [rates], heaviest first. */
    suspend fun heaviestUsers(
        from: LocalDate,
        until: LocalDate,
        rates: UsageRates,
        limit: Int,
    ): List<UserId>

    /** [users]' activity over the range; a user without any is left out. */
    suspend fun activityOf(
        users: Set<UserId>,
        from: LocalDate,
        until: LocalDate,
    ): Map<UserId, UserActivity>

    /** [users]' tokens by model over the range. */
    suspend fun tokensOf(
        users: Set<UserId>,
        from: LocalDate,
        until: LocalDate,
    ): Map<UserId, Map<String, TokenCounts>>
}
