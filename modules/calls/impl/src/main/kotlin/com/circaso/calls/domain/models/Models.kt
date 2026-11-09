package com.circaso.calls.domain.models

import com.circaso.calls.api.CallId
import com.circaso.integrations.api.IceServer
import com.circaso.messaging.api.ChatId
import com.circaso.shared.UserId
import java.time.Instant

enum class CallKind { VOICE, VIDEO }

enum class CallState { RINGING, ACTIVE, ENDED }

enum class ParticipantState { INVITED, JOINED, DECLINED, MISSED, LEFT }

enum class EndReason { COMPLETED, CANCELLED, MISSED, DECLINED }

/** A track a joined participant publishes: the name others pull it by, `audio` or `video`, and its mid at the SFU. */
data class CallTrack(val trackName: String, val kind: String, val mid: String)

/**
 * Someone the call rang, or who joined it. [sessionId] is their SFU session while `JOINED`; it never leaves the
 * server except to its owner.
 */
data class Participant(
    val userId: UserId,
    val state: ParticipantState,
    val sessionId: String?,
    val tracks: List<CallTrack>,
    val audio: Boolean,
    val video: Boolean,
    val joinedAt: Instant?,
    val updatedAt: Instant,
    /**
     * When their phone said its connection to the SFU under [sessionId] is up (CL3b); null while it connects. Others
     * pull this person's tracks once it is set: the SFU holds a pull of a publisher that isn't connected yet.
     */
    val connectedAt: Instant? = null,
) {
    val isJoined: Boolean get() = state == ParticipantState.JOINED
    val isConnected: Boolean get() = isJoined && connectedAt != null
}

/**
 * A voice or video call in a chat (decision 2026-10-29): media goes through Cloudflare Realtime's SFU, the server
 * only brokers sessions and rings people. One call at a time per chat; at most [MAX_JOINED] people in it.
 */
data class Call(
    val callId: CallId,
    val chatId: ChatId,
    val kind: CallKind,
    val state: CallState,
    val startedBy: UserId,
    val isGroup: Boolean,
    val createdAt: Instant,
    val startedAt: Instant?,
    val endedAt: Instant?,
    val endReason: EndReason?,
    /** The last join, leave, pull, renegotiation or media change: the idle sweep ends a call quiet for too long. */
    val activeAt: Instant,
    val participants: List<Participant>,
) {
    val joined: List<Participant> get() = participants.filter { it.isJoined }
    val isEnded: Boolean get() = state == CallState.ENDED

    fun participant(userId: UserId): Participant? = participants.firstOrNull { it.userId == userId }

    companion object {
        const val MAX_JOINED = 8
    }
}

/** A call as a person sees it: with names for the tiles and the ICE servers their phone connects through. */
data class CallView(val call: Call, val names: Map<UserId, String>, val iceServers: List<IceServer>)

/** A track the phone offers to publish when it joins. */
data class TrackOffer(val mid: String, val trackName: String, val kind: String)

data class JoinCommand(
    val userId: UserId,
    val callId: CallId,
    val offerSdp: String,
    val tracks: List<TrackOffer>,
)

/** The joiner's own session and the SFU's answer, with the call as it now is. */
data class Joined(val sessionId: String, val answerSdp: String, val view: CallView)

/** A track the phone wants to receive: whose, and by which name. */
data class TrackWanted(val userId: UserId, val trackName: String)

data class PullCommand(
    val userId: UserId,
    val callId: CallId,
    val sessionId: String,
    val tracks: List<TrackWanted>,
)

/** A track the SFU now sends the phone, at [mid] of the SFU's offer. */
data class PulledFrom(val userId: UserId, val trackName: String, val mid: String)

data class Pulled(val offerSdp: String?, val requiresRenegotiation: Boolean, val tracks: List<PulledFrom>)

data class RenegotiateCommand(
    val userId: UserId,
    val callId: CallId,
    val sessionId: String,
    val answerSdp: String,
)

/** CL3b: the phone's connection to the SFU under [sessionId] is up. */
data class ConnectedCommand(val userId: UserId, val callId: CallId, val sessionId: String)

data class MediaCommand(
    val userId: UserId,
    val callId: CallId,
    val audio: Boolean,
    val video: Boolean,
)
