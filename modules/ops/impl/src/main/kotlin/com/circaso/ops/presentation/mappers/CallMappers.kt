package com.circaso.ops.presentation.mappers

import com.circaso.calls.api.CallId
import com.circaso.calls.api.StaffCallLine
import com.circaso.calls.api.StaffCallReport
import com.circaso.ops.domain.models.CallSheet
import com.circaso.ops.presentation.dto.CallDetailResponse
import com.circaso.ops.presentation.dto.CallLineDto
import com.circaso.ops.presentation.dto.CallParticipantDto
import com.circaso.ops.presentation.dto.CallReportDto
import com.circaso.ops.presentation.dto.CallReportEventDto
import com.circaso.ops.presentation.dto.CallReportSampleDto
import com.circaso.ops.presentation.dto.CallReportSummaryDto
import com.circaso.shared.Resource
import java.time.Duration

fun parseCallId(raw: String?): Resource<CallId> =
    raw?.let(CallId::parse)?.let { Resource.success(it) } ?: malformed("callId", "must be a call id")

/** From the second person joining to the end; null while it goes on or when nobody answered. */
private fun StaffCallLine.durationSeconds(): Long? {
    val from = startedAt ?: return null
    val to = endedAt ?: return null
    return Duration.between(from, to).seconds
}

fun StaffCallLine.toDto() =
    CallLineDto(
        callId = callId.toString(),
        chatId = chatId.toString(),
        kind = kind,
        state = state,
        endReason = endReason,
        isGroup = isGroup,
        startedBy = startedBy.toString(),
        createdAt = createdAt.toString(),
        startedAt = startedAt?.toString(),
        endedAt = endedAt?.toString(),
        durationSeconds = durationSeconds(),
        participants = participants,
        reports = reports,
    )

fun CallSheet.toResponse(): CallDetailResponse {
    val line = call.line
    return CallDetailResponse(
        callId = line.callId.toString(),
        chatId = line.chatId.toString(),
        kind = line.kind,
        state = line.state,
        endReason = line.endReason,
        isGroup = line.isGroup,
        startedBy = line.startedBy.toString(),
        createdAt = line.createdAt.toString(),
        startedAt = line.startedAt?.toString(),
        endedAt = line.endedAt?.toString(),
        durationSeconds = line.durationSeconds(),
        participants =
            call.participants.map { participant ->
                val person = people[participant.userId]
                CallParticipantDto(
                    userId = participant.userId.toString(),
                    name = person?.displayName,
                    handle = person?.handle,
                    state = participant.state,
                    joinedAt = participant.joinedAt?.toString(),
                    leftCause = participant.leftCause,
                    report = participant.report?.toDto(),
                )
            },
    )
}

private fun StaffCallReport.toDto() =
    CallReportDto(
        platform = platform,
        appVersion = appVersion,
        osVersion = osVersion,
        deviceModel = deviceModel,
        video = video,
        outgoing = outgoing,
        startedAt = startedAt.toString(),
        endedAt = endedAt.toString(),
        receivedAt = receivedAt.toString(),
        endCause = endCause,
        summary =
            CallReportSummaryDto(
                summary.durationMs,
                summary.samples,
                summary.avgRttMs,
                summary.maxRttMs,
                summary.avgJitterMs,
                summary.maxJitterMs,
                summary.lossPct,
                summary.packetsReceived,
                summary.bytesReceived,
                summary.reconnects,
            ),
        events = events.map { CallReportEventDto(it.t, it.type, it.detail) },
        samples =
            samples.map {
                CallReportSampleDto(it.t, it.rttMs, it.jitterMs, it.lossPct, it.packetsReceived, it.bytesReceived)
            },
    )
