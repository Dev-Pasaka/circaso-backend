package com.circaso.assistant.domain.models

import com.circaso.shared.UserId
import java.time.LocalDate

// What the staff cost report reads (decision 2026-11-08): sums over `usage_daily` and `usage_daily_tokens`.

/** One UTC day's activity across every user; [users] is how many used Asha that day (they have a usage row). */
data class DayActivity(
    val day: LocalDate,
    val textTurns: Long,
    val voiceSeconds: Long,
    val webSearches: Long,
    val users: Long,
)

/** One user's activity over a range. */
data class UserActivity(
    val userId: UserId,
    val textTurns: Long,
    val voiceSeconds: Long,
    val webSearches: Long,
)

/** A UTC day of usage across every user, its tokens by model. */
data class UsageDay(val activity: DayActivity, val tokens: Map<String, TokenCounts>)

/** One user's usage over a range, its tokens by model. */
data class UsageUser(val activity: UserActivity, val tokens: Map<String, TokenCounts>)
