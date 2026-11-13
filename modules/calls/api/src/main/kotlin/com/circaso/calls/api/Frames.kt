package com.circaso.calls.api

import com.circaso.messaging.api.ChatId
import com.circaso.shared.UserId
import kotlinx.serialization.Serializable

/**
 * The `data` of each realtime frame `calls` sends, one class per type (decision 2026-10-27). They live in the
 * contract so the gateway reads them typed when it words a push.
 */
object CallFrames {
    const val MODULE = "calls"
    const val RINGING = "call.ringing"
    const val UPDATED = "call.updated"
    const val ENDED = "call.ended"
    const val RING_STOPPED = "call.ring_stopped"
    const val HEARTBEAT = "call.heartbeat"
}

/**
 * `calls/call.heartbeat` (2026-11-13): [userId]'s phone said it is still on [callId], relayed at once to everyone else
 * joined. Phones in a call send `calls/heartbeat` every 2 seconds, so a phone that stops hearing someone's heartbeat
 * knows within a few seconds that their phone has gone quiet. Never kept, never replayed (`seq` 0), never pushed.
 */
@Serializable
data class CallHeartbeatFrame(val callId: CallId, val userId: UserId)

/**
 * `calls/call.ring_stopped`, once, to each person whose ring stopped without them answering or declining it
 * (2026-11-09): the caller hung up, the call ended, or their 45 seconds ran out while a group call went on. The
 * gateway turns it into an "ended" VoIP push for an iPhone, so CallKit stops ringing on a phone whose app is
 * suspended and whose socket is gone. [reason] is `CANCELLED`, `MISSED`, `DECLINED` or `COMPLETED` (the call ended
 * that way, or `DROPPED`) or `MISSED` (the ring ran out in a call that goes on).
 */
@Serializable
data class CallRingStoppedFrame(val callId: CallId, val chatId: ChatId, val reason: String)

/**
 * `calls/call.ringing`, to each person invited: someone is calling in the chat. Pushed data-only and high priority
 * to a phone without a socket, so it can show the incoming-call screen. [kind] is `VOICE` or `VIDEO`.
 */
@Serializable
data class CallRingingFrame(
    val callId: CallId,
    val chatId: ChatId,
    val kind: String,
    val callerId: UserId,
    val callerName: String,
)

/**
 * The call as everyone in it sees it: the `data` of `calls/call.updated` (someone joined, left, declined, muted or
 * turned the camera off) and of `calls/call.ended`. The same shape as the REST `CallResponse`, except that
 * [iceServers] is empty in a frame: TURN credentials are handed out on the REST answers only, never kept in the
 * frame log. Another participant's SFU session never appears; the server maps a user to their session.
 */
@Serializable
data class CallSnapshot(
    val callId: CallId,
    val chatId: ChatId,
    /** `VOICE` or `VIDEO`. */
    val kind: String,
    /** `RINGING`, `ACTIVE` or `ENDED`. */
    val state: String,
    val startedBy: UserId,
    val createdAt: String,
    val startedAt: String? = null,
    val endedAt: String? = null,
    /** `COMPLETED`, `CANCELLED`, `MISSED`, `DECLINED` or `DROPPED` (2026-11-13: the line dropped) once `ENDED`. */
    val endReason: String? = null,
    val participants: List<CallParticipantSnapshot>,
    val iceServers: List<IceServerSnapshot> = emptyList(),
)

@Serializable
data class CallParticipantSnapshot(
    val userId: UserId,
    val displayName: String,
    /** `INVITED`, `JOINED`, `DECLINED`, `MISSED` or `LEFT`. */
    val state: String,
    val audio: Boolean,
    val video: Boolean,
    /** What the participant publishes while `JOINED`; pull them by name (CL4). */
    val tracks: List<CallTrackSnapshot>,
    val joinedAt: String? = null,
    /** Changes every time this person (re)joins: pull their tracks again when it does. */
    val connection: String? = null,
    /** Their phone's SFU connection is up under [connection] (CL3b): their tracks are ready to pull. */
    val connected: Boolean = false,
    /** Their video is their screen, not their camera (CL8): show it whole and say they are sharing. */
    val screen: Boolean = false,
)

@Serializable
data class CallTrackSnapshot(
    val trackName: String,
    /** `audio` or `video`. */
    val kind: String,
)

@Serializable
data class IceServerSnapshot(val urls: List<String>, val username: String? = null, val credential: String? = null)
