package com.circaso.calls.presentation.mappers

import com.circaso.calls.api.CallId
import com.circaso.calls.domain.models.CallView
import com.circaso.calls.domain.models.JoinCommand
import com.circaso.calls.domain.models.Joined
import com.circaso.calls.domain.models.MediaCommand
import com.circaso.calls.domain.models.PullCommand
import com.circaso.calls.domain.models.Pulled
import com.circaso.calls.domain.models.RenegotiateCommand
import com.circaso.calls.domain.models.TrackOffer
import com.circaso.calls.domain.models.TrackWanted
import com.circaso.calls.presentation.dto.CallResponse
import com.circaso.calls.presentation.dto.IceServerDto
import com.circaso.calls.presentation.dto.JoinRequest
import com.circaso.calls.presentation.dto.JoinResponse
import com.circaso.calls.presentation.dto.MediaRequest
import com.circaso.calls.presentation.dto.ParticipantDto
import com.circaso.calls.presentation.dto.PullRequest
import com.circaso.calls.presentation.dto.PullResponse
import com.circaso.calls.presentation.dto.PulledTrackDto
import com.circaso.calls.presentation.dto.RenegotiateRequest
import com.circaso.calls.presentation.dto.RingRequest
import com.circaso.calls.presentation.dto.TrackDto
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId

private const val MAX_TRACKS = 4
private const val MAX_TRACK_NAME = 64
private const val MAX_SDP = 64 * 1024
private val TRACK_KINDS = setOf("audio", "video")

fun chatIdOf(raw: String?): Resource<ChatId> =
    raw?.let { ChatId.parse(it) }?.let { Resource.success(it) }
        ?: Resource.error(SharedError.Malformed("chatId", "is not a chat id"))

fun callIdOf(raw: String?): Resource<CallId> =
    raw?.let { CallId.parse(it) }?.let { Resource.success(it) }
        ?: Resource.error(SharedError.Malformed("callId", "is not a call id"))

fun JoinRequest.toCommand(userId: UserId, callId: CallId): Resource<JoinCommand> {
    sdpProblem("offer", offer)?.let { return Resource.error(it) }
    if (tracks.isEmpty() || tracks.size > MAX_TRACKS) {
        return Resource.error(SharedError.Malformed("tracks", "must list 1 to $MAX_TRACKS tracks"))
    }
    tracks.forEachIndexed { index, track ->
        val problem =
            when {
                track.mid.isBlank() -> "mid is empty"
                track.trackName.isBlank() || track.trackName.length > MAX_TRACK_NAME -> "trackName is empty or long"
                track.kind !in TRACK_KINDS -> "kind must be audio or video"
                else -> null
            }
        if (problem != null) return Resource.error(SharedError.Malformed("tracks[$index]", problem))
    }
    if (tracks.map { it.trackName }.toSet().size != tracks.size) {
        return Resource.error(SharedError.Malformed("tracks", "names a track twice"))
    }
    return Resource.success(
        JoinCommand(userId, callId, offer, tracks.map { TrackOffer(it.mid, it.trackName, it.kind) }),
    )
}

fun PullRequest.toCommand(userId: UserId, callId: CallId): Resource<PullCommand> {
    if (sessionId.isBlank()) return Resource.error(SharedError.Malformed("sessionId", "is empty"))
    val wanted =
        tracks.mapIndexed { index, track ->
            val owner =
                UserId.parse(track.userId)
                    ?: return Resource.error(SharedError.Malformed("tracks[$index].userId", "is not a user id"))
            TrackWanted(owner, track.trackName)
        }
    return Resource.success(PullCommand(userId, callId, sessionId, wanted))
}

fun RenegotiateRequest.toCommand(userId: UserId, callId: CallId): Resource<RenegotiateCommand> {
    if (sessionId.isBlank()) return Resource.error(SharedError.Malformed("sessionId", "is empty"))
    sdpProblem("answer", answer)?.let { return Resource.error(it) }
    return Resource.success(RenegotiateCommand(userId, callId, sessionId, answer))
}

fun MediaRequest.toCommand(userId: UserId, callId: CallId) = MediaCommand(userId, callId, audio, video)

fun CallView.toResponse(): CallResponse =
    CallResponse(
        callId = call.callId.toString(),
        chatId = call.chatId.toString(),
        kind = call.kind.name,
        state = call.state.name,
        startedBy = call.startedBy.toString(),
        createdAt = call.createdAt.toString(),
        startedAt = call.startedAt?.toString(),
        endedAt = call.endedAt?.toString(),
        endReason = call.endReason?.name,
        participants =
            call.participants.map { participant ->
                ParticipantDto(
                    userId = participant.userId.toString(),
                    displayName = names[participant.userId] ?: "Someone",
                    state = participant.state.name,
                    audio = participant.audio,
                    video = participant.video,
                    tracks = participant.tracks.map { TrackDto(it.trackName, it.kind) },
                    joinedAt = participant.joinedAt?.toString(),
                    connection =
                        com.circaso.calls.domain.support.Connection
                            .tag(participant.sessionId),
                )
            },
        iceServers = iceServers.map { IceServerDto(it.urls, it.username, it.credential) },
    )

fun Joined.toResponse() = JoinResponse(sessionId, answerSdp, view.toResponse())

fun Pulled.toResponse() =
    PullResponse(
        offer = offerSdp,
        requiresRenegotiation = requiresRenegotiation,
        tracks = tracks.map { PulledTrackDto(it.userId.toString(), it.trackName, it.mid) },
    )

private fun sdpProblem(field: String, sdp: String): SharedError? =
    when {
        sdp.isBlank() -> SharedError.Malformed(field, "is empty")
        sdp.length > MAX_SDP -> SharedError.Malformed(field, "is too long")
        else -> null
    }

fun RingRequest.toUserIds(): Resource<Set<UserId>> {
    val ids =
        userIds.mapIndexed { index, raw ->
            UserId.parse(raw) ?: return Resource.error(SharedError.Malformed("userIds[$index]", "is not a user id"))
        }
    return Resource.success(ids.toSet())
}
