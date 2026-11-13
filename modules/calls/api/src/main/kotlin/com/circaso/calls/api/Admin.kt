package com.circaso.calls.api

import com.circaso.messaging.api.ChatId
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Instant

/**
 * A call as staff see it in a list (2026-11-13). [kind] `VOICE` | `VIDEO`, [state] `RINGING` | `ACTIVE` | `ENDED`,
 * [endReason] as `CallSnapshot.endReason`. [participants] is everyone the call rang or who joined; [reports] how many
 * of their phones sent a report (CL10).
 */
data class StaffCallLine(
    val callId: CallId,
    val chatId: ChatId,
    val kind: String,
    val state: String,
    val endReason: String?,
    val isGroup: Boolean,
    val startedBy: UserId,
    val createdAt: Instant,
    val startedAt: Instant?,
    val endedAt: Instant?,
    val participants: Int,
    val reports: Int,
)

data class StaffCallPage(val items: List<StaffCallLine>, val nextCursor: String?)

/** One line of a phone's call timeline, as it sent it: [t] ms after the report's start. */
data class StaffReportEvent(val t: Long, val type: String, val detail: String)

/** One media sample; a number the phone couldn't read is null. */
data class StaffReportSample(
    val t: Long,
    val rttMs: Double?,
    val jitterMs: Double?,
    val lossPct: Double?,
    val packetsReceived: Long?,
    val bytesReceived: Long?,
)

/** The phone's own totals for the call; any number may be null. */
data class StaffReportSummary(
    val durationMs: Long?,
    val samples: Int?,
    val avgRttMs: Double?,
    val maxRttMs: Double?,
    val avgJitterMs: Double?,
    val maxJitterMs: Double?,
    val lossPct: Double?,
    val packetsReceived: Long?,
    val bytesReceived: Long?,
    val reconnects: Int?,
)

/** What one phone recorded about the call (CL10). No message content, phone numbers or names. */
data class StaffCallReport(
    val platform: String,
    val appVersion: String,
    val osVersion: String,
    val deviceModel: String,
    val video: Boolean,
    val outgoing: Boolean,
    val startedAt: Instant,
    val endedAt: Instant,
    val receivedAt: Instant,
    val endCause: String,
    val summary: StaffReportSummary,
    val events: List<StaffReportEvent>,
    val samples: List<StaffReportSample>,
)

/**
 * Someone the call rang or who joined it. [state] as `CallParticipantSnapshot.state`; [leftCause] what their phone said
 * when it left (CL7), null when it said nothing; [report] their phone's report, null when none came (or it expired).
 */
data class StaffCallParticipant(
    val userId: UserId,
    val state: String,
    val joinedAt: Instant?,
    val leftCause: String?,
    val report: StaffCallReport?,
)

/** One call for staff, with everyone in it. */
data class StaffCall(val line: StaffCallLine, val participants: List<StaffCallParticipant>)

/**
 * What the ops module may read of calls for the admin portal (2026-11-13, permission `calls.read`): a person's calls
 * and one call with its reports, to see why a call dropped. Read-only; implemented by calls, wired in `app`.
 */
interface CallsAdmin {
    /** [userId]'s calls, newest first, [limit] 1-200. Error: `request.malformed` for a cursor that isn't one. */
    suspend fun callsOf(
        userId: UserId,
        cursor: String?,
        limit: Int,
    ): Resource<StaffCallPage>

    /** The call with everyone in it, or null when there is no such call. */
    suspend fun call(callId: CallId): StaffCall?
}
