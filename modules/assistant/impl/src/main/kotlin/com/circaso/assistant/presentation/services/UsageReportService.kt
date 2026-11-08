package com.circaso.assistant.presentation.services

import com.circaso.assistant.api.UsageRates
import com.circaso.assistant.domain.models.UsageDay
import com.circaso.assistant.domain.models.UsageUser
import com.circaso.assistant.domain.usecases.CountAshaUsers
import com.circaso.assistant.domain.usecases.ListUsageDays
import com.circaso.assistant.domain.usecases.RankUsageUsers
import com.circaso.shared.Resource
import java.time.LocalDate

/** Staff reads of Asha's usage (decision 2026-11-08), behind `AssistantReports`. */
class UsageReportService(
    private val listUsageDays: ListUsageDays,
    private val countAshaUsers: CountAshaUsers,
    private val rankUsageUsers: RankUsageUsers,
) {
    suspend fun days(from: LocalDate, until: LocalDate): Resource<List<UsageDay>> = listUsageDays(from, until)

    suspend fun users(from: LocalDate, until: LocalDate): Resource<Long> = countAshaUsers(from, until)

    suspend fun heaviest(
        from: LocalDate,
        until: LocalDate,
        rates: UsageRates,
        limit: Int,
    ): Resource<List<UsageUser>> = rankUsageUsers(from, until, rates, limit)
}
