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
    /** `COMPLETED`, `CANCELLED`, `MISSED` or `DECLINED` once `ENDED`. */
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

/** CL8 `POST /calls/{callId}/media`: whether the microphone and the camera are on. */
@Serializable
data class MediaRequest(val audio: Boolean, val video: Boolean)

/** `POST /calls/{callId}/ring`: who to ring again; empty or left out is everyone not on the call. */
@Serializable
data class RingRequest(val userIds: List<String> = emptyList())

/** `{}`: done, nothing to add. */
@Serializable
data object EmptyResponse
