package com.circaso.calls.domain.ports

import com.circaso.calls.api.CallId
import com.circaso.calls.domain.models.CallKind
import com.circaso.calls.domain.models.CallView
import com.circaso.messaging.api.ChatId
import com.circaso.shared.EventEnvelope
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Duration

/** A frame the domain names; `data/cache` words it on the wire and keeps it for replay. */
sealed interface CallFrame {
    data class Ringing(
        val callId: CallId,
        val chatId: ChatId,
        val kind: CallKind,
        val callerId: UserId,
        val callerName: String,
    ) : CallFrame

    data class Updated(val view: CallView) : CallFrame

    data class Ended(val view: CallView) : CallFrame

    /** [callId] stopped ringing this person without them answering or declining; [reason] as `CallRingStoppedFrame`. */
    data class RingStopped(val callId: CallId, val chatId: ChatId, val reason: String) : CallFrame
}

/** Sends a frame to the people named: to their sockets, or as a push where the gateway words one. */
interface FrameSender {
    suspend fun send(userIds: Collection<UserId>, frame: CallFrame)
}

/** The frames kept for replay after a reconnect. */
interface RealtimeLog {
    suspend fun since(
        userId: UserId,
        seq: Long,
        limit: Int,
    ): List<LoggedFrame>

    suspend fun latest(userId: UserId): Long?
}

data class LoggedFrame(val seq: Long, val json: String)

/** Calls' outbox rows: written in the transaction that ended the call, then published. */
interface CallsOutbox {
    suspend fun write(events: List<EventEnvelope>)
}

/** One operation, one transaction (working rules → Postgres and Exposed); repositories join it. */
interface Transactor {
    /** Runs [block] in one transaction; a block that answers `Resource.Error` rolls it all back. */
    suspend fun <T> attempt(operation: String, block: suspend () -> Resource<T>): Resource<T>
}

/**
 * How many call reports one person may send in a window (2026-11-13: 30 an hour). Lives in Redis (`cal:reports:{userId}`),
 * never in the database, so a phone stuck re-sending costs nothing there.
 */
interface ReportAllowance {
    /** Counts one report for [userId]; the seconds to wait when [max] in [window] is used up, else null. */
    suspend fun take(
        userId: UserId,
        max: Long,
        window: Duration,
    ): Long?
}

/**
 * The live frames that are never kept or replayed (`call.heartbeat`): one envelope to everyone named, `seq` 0, through
 * `Realtime.deliverMany`. A failure is logged and dropped; the next heartbeat follows in 2 seconds.
 */
interface LiveCallFrames {
    suspend fun heartbeat(
        userIds: Collection<UserId>,
        callId: CallId,
        from: UserId,
    )
}

/**
 * What a heartbeat needs from Redis (`cal:hb:*`), so a phone saying it is there every 2 seconds costs no database read
 * each time: at most one relay per person and call per second, and who is joined in the call, kept a few seconds.
 */
interface HeartbeatCache {
    /** True the first time [userId] beats for [callId] in this second; false for a phone repeating itself faster. */
    suspend fun take(callId: CallId, userId: UserId): Boolean

    /** The people joined in [callId] as last read, or null when not kept (or gone stale). */
    suspend fun joined(callId: CallId): Set<UserId>?

    suspend fun keepJoined(callId: CallId, userIds: Set<UserId>)
}
