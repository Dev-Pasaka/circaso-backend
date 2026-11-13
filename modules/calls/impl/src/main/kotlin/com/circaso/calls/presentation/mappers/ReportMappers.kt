package com.circaso.calls.presentation.mappers

import com.circaso.calls.api.CallId
import com.circaso.calls.api.StaffCall
import com.circaso.calls.api.StaffCallLine
import com.circaso.calls.api.StaffCallPage
import com.circaso.calls.api.StaffCallParticipant
import com.circaso.calls.api.StaffCallReport
import com.circaso.calls.api.StaffReportEvent
import com.circaso.calls.api.StaffReportSample
import com.circaso.calls.api.StaffReportSummary
import com.circaso.calls.domain.errors.CallsError
import com.circaso.calls.domain.models.CallLine
import com.circaso.calls.domain.models.CallLinePage
import com.circaso.calls.domain.models.CallRecord
import com.circaso.calls.domain.models.CallReport
import com.circaso.calls.domain.models.LeaveCause
import com.circaso.calls.domain.models.ReportEvent
import com.circaso.calls.domain.models.ReportSample
import com.circaso.calls.domain.models.ReportSummary
import com.circaso.calls.presentation.dto.CallReportRequest
import com.circaso.calls.presentation.dto.LeaveRequest
import com.circaso.calls.presentation.dto.ReportSummaryDto
import com.circaso.shared.CircasoJson
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import kotlinx.serialization.SerializationException
import java.time.Instant
import java.time.format.DateTimeParseException

/** CL10's request body may be at most this many bytes (64 KiB); a longer one is `calls.report_invalid`. */
const val MAX_REPORT_BYTES = 64L * 1024

/** CL7's optional body is a few bytes; anything longer is read as no cause, never refused. */
const val MAX_LEAVE_BYTES = 4L * 1024

private const val MAX_PLATFORM = 16
private const val MAX_VERSION = 40
private const val MAX_MODEL = 80
private const val MAX_CAUSE = 40
private const val MAX_EVENTS = 400
private const val MAX_SAMPLES = 720
private const val MAX_TYPE = 32
private const val MAX_DETAIL = 160

/**
 * CL7's cause from its optional body. Hanging up must always work, so a body that is empty, isn't JSON or names no
 * cause is a leave without one; a cause this build doesn't know is `OTHER`.
 */
fun leaveCauseOf(body: String?): LeaveCause? {
    if (body.isNullOrBlank()) return null
    val request =
        try {
            CircasoJson.decodeFromString(LeaveRequest.serializer(), body)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
    return request.cause?.takeIf { it.isNotBlank() }?.let { LeaveCause.parse(it) }
}

/** CL10's shape checks; each problem is `calls.report_invalid` naming the field. */
fun CallReportRequest.toReport(userId: UserId, callId: CallId): Resource<CallReport> {
    fun invalid(field: String, problem: String) = Resource.error(CallsError.ReportInvalid(field, problem))

    val platform =
        text(platform, MAX_PLATFORM) ?: return invalid("platform", "required, at most $MAX_PLATFORM characters")
    val appVersion =
        text(appVersion, MAX_VERSION) ?: return invalid("appVersion", "required, at most $MAX_VERSION characters")
    val osVersion =
        text(osVersion, MAX_VERSION, blank = true)
            ?: return invalid("osVersion", "required, at most $MAX_VERSION characters")
    val deviceModel =
        text(deviceModel, MAX_MODEL, blank = true)
            ?: return invalid("deviceModel", "required, at most $MAX_MODEL characters")
    val endCause = text(endCause, MAX_CAUSE) ?: return invalid("endCause", "required, at most $MAX_CAUSE characters")
    val video = video ?: return invalid("video", "required")
    val outgoing = outgoing ?: return invalid("outgoing", "required")
    val startedAt = instant(startedAt) ?: return invalid("startedAt", "required, an ISO-8601 time")
    val endedAt = instant(endedAt) ?: return invalid("endedAt", "required, an ISO-8601 time")
    if (events.size > MAX_EVENTS) return invalid("events", "at most $MAX_EVENTS")
    if (samples.size > MAX_SAMPLES) return invalid("samples", "at most $MAX_SAMPLES")
    val timeline =
        events.mapIndexed { index, event ->
            val t = event.t?.takeIf { it >= 0 } ?: return invalid("events[$index].t", "required, 0 or more")
            val type =
                text(event.type, MAX_TYPE)
                    ?: return invalid("events[$index].type", "required, at most $MAX_TYPE characters")
            if (event.detail.length >
                MAX_DETAIL
            ) {
                return invalid("events[$index].detail", "at most $MAX_DETAIL characters")
            }
            ReportEvent(t, type, event.detail)
        }
    val media =
        samples.mapIndexed { index, sample ->
            val t = sample.t?.takeIf { it >= 0 } ?: return invalid("samples[$index].t", "required, 0 or more")
            ReportSample(t, sample.rttMs, sample.jitterMs, sample.lossPct, sample.packetsReceived, sample.bytesReceived)
        }
    return Resource.success(
        CallReport(
            callId = callId,
            userId = userId,
            platform = platform,
            appVersion = appVersion,
            osVersion = osVersion,
            deviceModel = deviceModel,
            video = video,
            outgoing = outgoing,
            endCause = endCause,
            startedAt = startedAt,
            endedAt = endedAt,
            // stamped by the use case when it is stored
            receivedAt = Instant.EPOCH,
            summary = summary?.toModel() ?: ReportSummary(),
            events = timeline,
            samples = media,
        ),
    )
}

private fun text(
    raw: String?,
    max: Int,
    blank: Boolean = false,
): String? = raw?.trim()?.takeIf { (blank || it.isNotEmpty()) && it.length <= max }

private fun instant(raw: String?): Instant? =
    try {
        raw?.let { Instant.parse(it.trim()) }
    } catch (_: DateTimeParseException) {
        null
    }

private fun ReportSummaryDto.toModel() =
    ReportSummary(
        durationMs,
        samples,
        avgRttMs,
        maxRttMs,
        avgJitterMs,
        maxJitterMs,
        lossPct,
        packetsReceived,
        bytesReceived,
        reconnects,
    )

// ---- calls:api's staff view ------------------------------------------------------------------------------------

fun CallLinePage.toStaff() = StaffCallPage(items.map { it.toStaff() }, nextCursor)

fun CallLine.toStaff() =
    StaffCallLine(
        callId = callId,
        chatId = chatId,
        kind = kind.name,
        state = state.name,
        endReason = endReason?.name,
        isGroup = isGroup,
        startedBy = startedBy,
        createdAt = createdAt,
        startedAt = startedAt,
        endedAt = endedAt,
        participants = participants,
        reports = reports,
    )

fun CallRecord.toStaff(): StaffCall {
    val byUser = reports.associateBy { it.userId }
    val line =
        StaffCallLine(
            callId = call.callId,
            chatId = call.chatId,
            kind = call.kind.name,
            state = call.state.name,
            endReason = call.endReason?.name,
            isGroup = call.isGroup,
            startedBy = call.startedBy,
            createdAt = call.createdAt,
            startedAt = call.startedAt,
            endedAt = call.endedAt,
            participants = call.participants.size,
            reports = reports.size,
        )
    val participants =
        call.participants.map { participant ->
            StaffCallParticipant(
                userId = participant.userId,
                state = participant.state.name,
                joinedAt = participant.joinedAt,
                leftCause = participant.leftCause?.name,
                report = byUser[participant.userId]?.toStaff(),
            )
        }
    return StaffCall(line, participants)
}

private fun CallReport.toStaff() =
    StaffCallReport(
        platform = platform,
        appVersion = appVersion,
        osVersion = osVersion,
        deviceModel = deviceModel,
        video = video,
        outgoing = outgoing,
        startedAt = startedAt,
        endedAt = endedAt,
        receivedAt = receivedAt,
        endCause = endCause,
        summary =
            StaffReportSummary(
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
        events = events.map { StaffReportEvent(it.t, it.type, it.detail) },
        samples =
            samples.map {
                StaffReportSample(it.t, it.rttMs, it.jitterMs, it.lossPct, it.packetsReceived, it.bytesReceived)
            },
    )
