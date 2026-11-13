package com.circaso.calls.presentation.dto

import kotlinx.serialization.Serializable

/** CL1 `POST /calls`. */
@Serializable
data class StartCallRequest(val chatId: String, val video: Boolean = false)

/** A call as the phone shows it. Another participant's SFU session never appears: the server maps users (CL4). */
@Serializable
data class CallResponse(
    val callId: String,
    val chatId: String,
    /** `VOICE` or `VIDEO`. */
    val kind: String,
    /** `RINGING`, `ACTIVE` or `ENDED`. */
    val state: String,
    val startedBy: String,
    val createdAt: String,
    val startedAt: String? = null,
    val endedAt: String? = null,
    /** `COMPLETED`, `CANCELLED`, `MISSED`, `DECLINED` or `DROPPED` (nobody hung up: the line dropped) once `ENDED`. */
    val endReason: String? = null,
    val participants: List<ParticipantDto>,
    /** For the phone's `RTCPeerConnection`: STUN, and TURN with credentials valid for a day when configured. */
    val iceServers: List<IceServerDto>,
)

@Serializable
data class ParticipantDto(
    val userId: String,
    val displayName: String,
    /** `INVITED`, `JOINED`, `DECLINED`, `MISSED` or `LEFT`. */
    val state: String,
    val audio: Boolean,
    val video: Boolean,
    val tracks: List<TrackDto>,
    val joinedAt: String? = null,
    /** Changes every time this person (re)joins: pull their tracks again when it does. Null until they join. */
    val connection: String? = null,
    /**
     * Their phone's connection to the SFU is up (CL3b) under the current [connection]: pull their tracks now. False
     * while they connect, and again after each rejoin. Older apps never say it; pull them as before.
     */
    val connected: Boolean = false,
    /** Their video is their screen, not their camera: show it whole (fitted, not cropped) and say they are sharing. */
    val screen: Boolean = false,
)

@Serializable
data class TrackDto(val trackName: String, val kind: String)

@Serializable
data class IceServerDto(val urls: List<String>, val username: String? = null, val credential: String? = null)

/** CL3 `POST /calls/{callId}/join`: the phone's offer and the tracks in it. */
@Serializable
data class JoinRequest(val offer: String, val tracks: List<TrackOfferDto>)

@Serializable
data class TrackOfferDto(
    /** The transceiver's mid in [JoinRequest.offer]. */
    val mid: String,
    /** The name others pull it by, unique in the participant's session (for example `audio-<userId>`). */
    val trackName: String,
    /** `audio` or `video`. */
    val kind: String,
)

@Serializable
data class JoinResponse(val sessionId: String, val answer: String, val call: CallResponse)

/** CL3b `POST /calls/{callId}/connected`: this phone's peer connection under [sessionId] is `connected`. */
@Serializable
data class ConnectedRequest(val sessionId: String)

/** CL4 `POST /calls/{callId}/pull`. */
@Serializable
data class PullRequest(val sessionId: String, val tracks: List<WantedTrackDto>)

@Serializable
data class WantedTrackDto(val userId: String, val trackName: String)

@Serializable
data class PullResponse(
    /** The SFU's offer, to answer with CL5 when [requiresRenegotiation]; null when nothing was pulled. */
    val offer: String? = null,
    val requiresRenegotiation: Boolean,
    /** What was pulled and where: a track left out was not available (its owner left, or doesn't publish it). */
    val tracks: List<PulledTrackDto>,
)

@Serializable
data class PulledTrackDto(val userId: String, val trackName: String, val mid: String)

/** CL5 `POST /calls/{callId}/renegotiate`. */
@Serializable
data class RenegotiateRequest(val sessionId: String, val answer: String)

/**
 * CL8 `POST /calls/{callId}/media`: whether the microphone and the camera are on, and whether the video is the
 * phone's screen. [screen] is optional so older apps keep working.
 */
@Serializable
data class MediaRequest(val audio: Boolean, val video: Boolean, val screen: Boolean = false)

/** `POST /calls/{callId}/ring`: who to ring again; empty or left out is everyone not on the call. */
@Serializable
data class RingRequest(val userIds: List<String> = emptyList())

/** `{}`: done, nothing to add. */
@Serializable
data object EmptyResponse

/**
 * CL7 `POST /calls/{callId}/leave`'s optional body: why the phone left (2026-11-13). No body, an empty one or no
 * `Content-Type` is a leave without a cause, as older apps send it.
 */
@Serializable
data class LeaveRequest(
    /**
     * `USER_HANGUP`, `REMOTE_LEFT`, `STALL`, `RECONNECT_GAVE_UP`, `JOIN_FAILED`, `PERMISSION_DENIED`, `ENGINE_FAILED`,
     * `APP_KILLED` or `OTHER`; any other is kept as `OTHER`.
     */
    val cause: String? = null,
)

/**
 * CL10 `POST /calls/{callId}/report` (2026-11-13): what this phone recorded about the call, sent once after it ended.
 * Every field is checked by the server (`calls.report_invalid` names the one that is wrong), so all are optional here.
 */
@Serializable
data class CallReportRequest(
    /** `android` or `ios`, at most 16 characters. */
    val platform: String? = null,
    /** At most 40 characters, for example `1.4.2 (61)`. */
    val appVersion: String? = null,
    /** At most 40 characters. */
    val osVersion: String? = null,
    /** At most 80 characters. */
    val deviceModel: String? = null,
    val video: Boolean? = null,
    /** This phone started the call. */
    val outgoing: Boolean? = null,
    /** When this phone's timeline began (ISO-8601). */
    val startedAt: String? = null,
    val endedAt: String? = null,
    /** CL7's causes, plus `DECLINED`, `REMOTE_ENDED`, `RING_STOPPED`, `ANSWERED_ELSEWHERE`; at most 40 characters. */
    val endCause: String? = null,
    /** At most 400, oldest first. */
    val events: List<ReportEventDto> = emptyList(),
    /** At most 720, one about every 5 seconds. */
    val samples: List<ReportSampleDto> = emptyList(),
    val summary: ReportSummaryDto? = null,
)

/** One line of the timeline: [t] ms after `startedAt` (0 or more), [type] at most 32 characters, [detail] at most 160. */
@Serializable
data class ReportEventDto(val t: Long? = null, val type: String? = null, val detail: String = "")

/** One media sample; every number but [t] may be null. */
@Serializable
data class ReportSampleDto(
    val t: Long? = null,
    val rttMs: Double? = null,
    val jitterMs: Double? = null,
    val lossPct: Double? = null,
    val packetsReceived: Long? = null,
    val bytesReceived: Long? = null,
)

/** The phone's totals; every number may be null. */
@Serializable
data class ReportSummaryDto(
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

@Serializable
data class ReportReceivedResponse(val received: Boolean = true)
