package com.circaso.calls.domain.models

import com.circaso.calls.api.CallId
import com.circaso.messaging.api.ChatId
import com.circaso.shared.UserId
import java.time.Instant

/** One line of a phone's call timeline: [t] ms after the report's start, its [type] and [detail], kept as sent. */
data class ReportEvent(val t: Long, val type: String, val detail: String)

/** One media sample, about every 5 seconds; any number the phone couldn't read is null. */
data class ReportSample(
    val t: Long,
    val rttMs: Double?,
    val jitterMs: Double?,
    val lossPct: Double?,
    val packetsReceived: Long?,
    val bytesReceived: Long?,
)

/** The phone's own totals for the call; any number may be null. */
data class ReportSummary(
    val durationMs: Long? = null,
    val samples: Int? = null,
    val avgRttMs: Double? = null,
    val maxRttMs: Double? = null,
    val avgJitterMs: Double? = null,
    val maxJitterMs: Double? = null,
    val lossPct: Double? = null,
    val packetsReceived: Long? = null,
    val bytesReceived: Long? = null,
    val reconnects: Int? = null,
)

/**
 * What one phone recorded about one call (CL10, 2026-11-13), sent after it ended, perhaps hours later. No message
 * content, phone numbers or names; the server stores it as given and never logs it. One per person per call: a repeat
 * replaces it.
 */
data class CallReport(
    val callId: CallId,
    val userId: UserId,
    val platform: String,
    val appVersion: String,
    val osVersion: String,
    val deviceModel: String,
    val video: Boolean,
    val outgoing: Boolean,
    val endCause: String,
    val startedAt: Instant,
    val endedAt: Instant,
    val receivedAt: Instant,
    val summary: ReportSummary,
    val events: List<ReportEvent>,
    val samples: List<ReportSample>,
)

/** A call as a line of a person's call list for staff: how it went, how many people it rang and how many reports. */
data class CallLine(
    val callId: CallId,
    val chatId: ChatId,
    val kind: CallKind,
    val state: CallState,
    val endReason: EndReason?,
    val isGroup: Boolean,
    val startedBy: UserId,
    val createdAt: Instant,
    val startedAt: Instant?,
    val endedAt: Instant?,
    val participants: Int,
    val reports: Int,
)

data class CallLinePage(val items: List<CallLine>, val nextCursor: String?)

/** A call for staff: every participant and the report each phone sent, if any. */
data class CallRecord(val call: Call, val reports: List<CallReport>)
