package com.circaso.calls.domain.ports

import com.circaso.calls.api.CallId
import com.circaso.calls.domain.models.CallKind
import com.circaso.calls.domain.models.CallView
import com.circaso.messaging.api.ChatId
import com.circaso.shared.EventEnvelope
import com.circaso.shared.Resource
import com.circaso.shared.UserId

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
