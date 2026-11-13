package com.circaso.ops.presentation.dto

import kotlinx.serialization.Serializable

/**
 * One call in a person's call list (2026-11-13). `kind` `VOICE` | `VIDEO`; `state` `RINGING` | `ACTIVE` | `ENDED`;
 * `endReason` `COMPLETED` | `CANCELLED` | `MISSED` | `DECLINED` | `DROPPED`, null until it ended. `durationSeconds`
 * from the second person joining to the end, null while it goes on or when nobody answered. `participants`: everyone
 * it rang or who joined; `reports`: how many of their phones sent a report.
 */
@Serializable
data class CallLineDto(
    val callId: String,
    val chatId: String,
    val kind: String,
    val state: String,
    val endReason: String?,
    val isGroup: Boolean,
    val startedBy: String,
    val createdAt: String,
    val startedAt: String?,
    val endedAt: String?,
    val durationSeconds: Long?,
    val participants: Int,
    val reports: Int,
)

@Serializable
data class CallListResponse(val items: List<CallLineDto>, val nextCursor: String?)

@Serializable
data class CallReportEventDto(val t: Long, val type: String, val detail: String)

@Serializable
data class CallReportSampleDto(
    val t: Long,
    val rttMs: Double?,
    val jitterMs: Double?,
    val lossPct: Double?,
    val packetsReceived: Long?,
    val bytesReceived: Long?,
)

@Serializable
data class CallReportSummaryDto(
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

/** What one phone recorded about the call, as it sent it (app CL10); `t` is ms after `startedAt`. */
@Serializable
data class CallReportDto(
    val platform: String,
    val appVersion: String,
    val osVersion: String,
    val deviceModel: String,
    val video: Boolean,
    val outgoing: Boolean,
    val startedAt: String,
    val endedAt: String,
    val receivedAt: String,
    val endCause: String,
    val summary: CallReportSummaryDto,
    val events: List<CallReportEventDto>,
    val samples: List<CallReportSampleDto>,
)

/**
 * Someone the call rang or who joined it. `name` and `handle` from the person's profile (null without one); `state`
 * `INVITED` | `JOINED` | `DECLINED` | `MISSED` | `LEFT`; `leftCause` what their phone said when it left (null when it
 * said nothing); `report` null when their phone sent none (or it is over 30 days old).
 */
@Serializable
data class CallParticipantDto(
    val userId: String,
    val name: String?,
    val handle: String?,
    val state: String,
    val joinedAt: String?,
    val leftCause: String?,
    val report: CallReportDto?,
)

@Serializable
data class CallDetailResponse(
    val callId: String,
    val chatId: String,
    val kind: String,
    val state: String,
    val endReason: String?,
    val isGroup: Boolean,
    val startedBy: String,
    val createdAt: String,
    val startedAt: String?,
    val endedAt: String?,
    val durationSeconds: Long?,
    val participants: List<CallParticipantDto>,
)
