package com.circaso.calls.domain.models

import com.circaso.calls.api.CallId
import com.circaso.integrations.api.IceServer
import com.circaso.messaging.api.ChatId
import com.circaso.shared.UserId
import java.time.Instant

enum class CallKind { VOICE, VIDEO }

enum class CallState { RINGING, ACTIVE, ENDED }

enum class ParticipantState { INVITED, JOINED, DECLINED, MISSED, LEFT }

/**
 * How a call ended. [DROPPED] (2026-11-13): nobody hung up on purpose. The leave that ended it said the media stalled
 * or reconnecting gave up, or the idle sweep ended a call every phone had gone quiet on.
 */
enum class EndReason { COMPLETED, CANCELLED, MISSED, DECLINED, DROPPED }

/**
 * Why a phone left a call, as it says on CL7 (2026-11-13). Stored on the leaver's row for staff to read; a cause this
 * build doesn't know is kept as [OTHER], never refused.
 */
enum class LeaveCause {
    USER_HANGUP,
    REMOTE_LEFT,
    STALL,
    RECONNECT_GAVE_UP,

    /** The other person's phone went quiet (no media, no heartbeat) and never came back (2026-11-13). */
    PEER_LOST,
    JOIN_FAILED,
    PERMISSION_DENIED,
    ENGINE_FAILED,
    APP_KILLED,
    OTHER,
    ;

    /** The phone lost the call rather than hanging up: a leave for this that ends the call ends it `DROPPED`. */
    val isDrop: Boolean get() = this == STALL || this == RECONNECT_GAVE_UP || this == PEER_LOST

    companion object {
        fun parse(raw: String): LeaveCause = entries.firstOrNull { it.name == raw.trim().uppercase() } ?: OTHER
    }
}

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
    /**
     * Their video track carries their screen, not their camera (CL8, 2026-11-09): the others show it whole, never
     * cropped, and say they are sharing. The phone swaps what feeds the track; nothing is renegotiated.
     */
    val screen: Boolean = false,
    /** Why they last left, when their phone said (CL7); cleared when they join again. */
    val leftCause: LeaveCause? = null,
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
    /**
     * Pull everyone already connected in the same request (2026-11-13), while the joiner's own connection is still
     * fresh: the SFU answers a pull on a new session in ~0.3 s, on one that is connected in 0.8 to 2.6 s.
     */
    val pullConnected: Boolean = false,
)

/**
 * The joiner's own session and the SFU's answer, with the call as it now is; [pulled] is the pull of everyone already
 * connected when the phone asked for it ([JoinCommand.pullConnected]), null when nothing was pulled or the pull
 * failed (the phone then pulls with CL4 as before).
 */
data class Joined(
    val sessionId: String,
    val answerSdp: String,
    val view: CallView,
    val pulled: Pulled? = null,
)

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

/** A phone in a call says it is still there (`calls/heartbeat`, 2026-11-13). */
data class HeartbeatCommand(val userId: UserId, val callId: CallId)

/** CL3b: the phone's connection to the SFU under [sessionId] is up. */
data class ConnectedCommand(val userId: UserId, val callId: CallId, val sessionId: String)

data class MediaCommand(
    val userId: UserId,
    val callId: CallId,
    val audio: Boolean,
    val video: Boolean,
    /** The video track carries the screen; older apps never send it. */
    val screen: Boolean = false,
)
