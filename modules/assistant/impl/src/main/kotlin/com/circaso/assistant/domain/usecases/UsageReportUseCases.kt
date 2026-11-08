package com.circaso.assistant.domain.usecases

import com.circaso.assistant.api.UsageRates
import com.circaso.assistant.domain.models.DayActivity
import com.circaso.assistant.domain.models.UsageDay
import com.circaso.assistant.domain.models.UsageUser
import com.circaso.assistant.domain.models.UserActivity
import com.circaso.assistant.domain.repositories.UsageReportRepository
import com.circaso.assistant.domain.support.UsageRange
import com.circaso.shared.Resource
import java.time.LocalDate

// Staff: what Asha used (decision 2026-11-08), read-only, for the ops cost report. Ranges are UTC days, at most 366.

/** Every day of `[from, until)` with any use, oldest first: its activity and its tokens by model. */
class ListUsageDays(private val reports: UsageReportRepository) {
    suspend operator fun invoke(from: LocalDate, until: LocalDate): Resource<List<UsageDay>> {
        UsageRange.check(from, until)
        val activity = reports.activityByDay(from, until).associateBy { it.day }
        val tokens = reports.tokensByDay(from, until)
        val days =
            (activity.keys + tokens.keys).sorted().map { day ->
                UsageDay(activity[day] ?: DayActivity(day, 0, 0, 0, 0), tokens[day].orEmpty())
            }
        return Resource.success(days)
    }
}

/** How many different users used Asha in `[from, until)`. */
class CountAshaUsers(private val reports: UsageReportRepository) {
    suspend operator fun invoke(from: LocalDate, until: LocalDate): Resource<Long> {
        UsageRange.check(from, until)
        return Resource.success(reports.users(from, until))
    }
}

/** The [limit] users whose use of `[from, until)` costs most at [rates], most first, with their sums. */
class RankUsageUsers(private val reports: UsageReportRepository) {
    suspend operator fun invoke(
        from: LocalDate,
        until: LocalDate,
        rates: UsageRates,
        limit: Int,
    ): Resource<List<UsageUser>> {
        UsageRange.check(from, until)
        val ids = reports.heaviestUsers(from, until, rates, limit.coerceIn(1, MAX_USERS))
        val wanted = ids.toSet()
        val activity = reports.activityOf(wanted, from, until)
        val tokens = reports.tokensOf(wanted, from, until)
        return Resource.success(
            ids.map { id -> UsageUser(activity[id] ?: UserActivity(id, 0, 0, 0), tokens[id].orEmpty()) },
        )
    }

    private companion object {
        const val MAX_USERS = 200
    }
}
