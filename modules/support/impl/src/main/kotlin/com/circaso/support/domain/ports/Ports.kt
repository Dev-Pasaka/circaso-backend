package com.circaso.support.domain.ports

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.support.api.SupportFrame
import java.time.Duration

/**
 * One operation, one transaction (working rules → Postgres and Exposed): the conditional ticket update and its
 * message run inside. Repositories called inside join the open transaction; frames come after the commit.
 */
interface Transactor {
    /** Runs [block] in one transaction; [operation] is a string literal naming it in the log line. */
    suspend fun <T> run(operation: String, block: suspend () -> T): T

    /** Like [run]; a block that answers `Resource.Error` rolls everything it wrote back. */
    suspend fun <T> attempt(operation: String, block: suspend () -> Resource<T>): Resource<T>
}

/**
 * How many new tickets one user may open in a day (decision 2026-11-08: 5, `SUPPORT_TICKETS_PER_DAY`). Lives in Redis
 * (`sup:opened:{userId}`), never in the database, so a burst costs nothing there.
 */
interface TicketAllowance {
    /** Counts one new ticket for [userId]; the seconds to wait when [max] in [window] is used up, else null. */
    suspend fun take(
        userId: UserId,
        max: Long,
        window: Duration,
    ): Long?
}

/** Sends a frame to the ticket's user at their next support `seq` and keeps it 7 days for replay. */
interface FrameSender {
    suspend fun send(userId: UserId, frame: SupportFrame)
}

/** The frames support has kept, so a phone that reconnects can catch up. */
interface RealtimeLog {
    /** Frames after [seq], oldest first. */
    suspend fun since(
        userId: UserId,
        seq: Long,
        limit: Int,
    ): List<LoggedFrame>

    /** The latest `seq` handed out for this user, or null if none is remembered. */
    suspend fun latest(userId: UserId): Long?
}

data class LoggedFrame(val seq: Long, val json: String)
