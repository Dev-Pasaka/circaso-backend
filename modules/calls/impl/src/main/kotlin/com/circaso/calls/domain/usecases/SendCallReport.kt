package com.circaso.calls.domain.usecases

import com.circaso.calls.domain.errors.CallsError
import com.circaso.calls.domain.models.CallReport
import com.circaso.calls.domain.ports.ReportAllowance
import com.circaso.calls.domain.repositories.CallReportRepository
import com.circaso.calls.domain.repositories.CallRepository
import com.circaso.shared.Resource
import java.time.Clock
import java.time.Duration

/**
 * CL10 `POST /calls/{callId}/report` (2026-11-13): a phone's timeline and media numbers for a call, sent once after it
 * ended, perhaps hours later. Only someone the call rang or who joined it may send one; a repeat replaces the earlier
 * one. At most [MAX_PER_HOUR] an hour per person. Stored as sent, stamped with when it came; never logged.
 */
class SendCallReport(
    private val calls: CallRepository,
    private val reports: CallReportRepository,
    private val allowance: ReportAllowance,
    private val clock: Clock,
) {
    suspend operator fun invoke(report: CallReport): Resource<Unit> {
        allowance.take(report.userId, MAX_PER_HOUR, WINDOW)?.let {
            return Resource.error(CallsError.ReportRateLimited(it))
        }
        val call = calls.find(report.callId)
        if (call?.participant(report.userId) == null) return Resource.error(CallsError.NotFound)
        reports.upsert(report.copy(receivedAt = clock.instant()))
        return Resource.success(Unit)
    }

    private companion object {
        const val MAX_PER_HOUR = 30L
        val WINDOW: Duration = Duration.ofHours(1)
    }
}
