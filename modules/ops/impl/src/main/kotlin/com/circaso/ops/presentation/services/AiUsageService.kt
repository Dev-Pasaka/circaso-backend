package com.circaso.ops.presentation.services

import com.circaso.ops.domain.models.AiUsageReport
import com.circaso.ops.domain.models.UsageBucket
import com.circaso.ops.domain.usecases.GetAiUsageReport
import com.circaso.shared.Resource
import java.time.LocalDate

/** What Asha's model use costs (decision 2026-11-08), read-only, for FINANCE and ADMIN. */
class AiUsageService(private val getAiUsageReport: GetAiUsageReport) {
    suspend fun report(
        from: LocalDate,
        until: LocalDate,
        bucket: UsageBucket,
        limit: Int,
    ): Resource<AiUsageReport> = getAiUsageReport(from, until, bucket, limit)
}
