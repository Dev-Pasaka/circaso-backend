package com.circaso.assistant.presentation.api

import com.circaso.assistant.api.AshaUsageDay
import com.circaso.assistant.api.AshaUsageUser
import com.circaso.assistant.api.AssistantReports
import com.circaso.assistant.api.UsageRates
import com.circaso.assistant.presentation.mappers.toApi
import com.circaso.assistant.presentation.services.UsageReportService
import com.circaso.shared.getOrThrow
import java.time.LocalDate

/**
 * assistant:api's staff contract (decision 2026-11-08), bound for the ops module only: the app gateway never mounts
 * it. Read-only; every call goes through [UsageReportService], like any other way in.
 */
class AssistantReportsImpl(private val reports: UsageReportService) : AssistantReports {
    override suspend fun days(from: LocalDate, until: LocalDate): List<AshaUsageDay> =
        reports.days(from, until).getOrThrow().map { it.toApi() }

    override suspend fun activeUsers(from: LocalDate, until: LocalDate): Long = reports.users(from, until).getOrThrow()

    override suspend fun topUsers(
        from: LocalDate,
        until: LocalDate,
        rates: UsageRates,
        limit: Int,
    ): List<AshaUsageUser> = reports.heaviest(from, until, rates, limit).getOrThrow().map { it.toApi() }
}
