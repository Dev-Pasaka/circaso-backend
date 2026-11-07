package com.circaso.assistant.domain.ports

import com.circaso.assistant.domain.models.AshaStatus
import com.circaso.assistant.domain.models.ProbeState
import com.circaso.shared.UserId
import java.time.Duration
import java.time.Instant

/**
 * Asha's health, shared by every node (decision 2026-11-07: Claude only, no fallback model). She is down from the
 * first turn her model couldn't answer until the first answer after it, a real turn's or the health monitor's probe.
 * Who met her down is kept (ids only, nothing they asked), so each of them hears once that she is back.
 */
interface AshaHealth {
    suspend fun status(): AshaStatus

    /** Marks her down from [at], unless she already is; true if this call did. */
    suspend fun markDown(at: Instant): Boolean

    /** Marks her up from [at], if she was down; true if this call did (exactly one caller wins). */
    suspend fun markUp(at: Instant): Boolean

    /** [userId] met her down; kept a day at most. */
    suspend fun recordAffected(userId: UserId)

    /** Every user recorded since the last take, and empties the list in the same step. */
    suspend fun takeAffected(): List<UserId>

    /** The one "she's back" notice [userId] may get within [window]: true if this call claimed it. */
    suspend fun claimNotice(userId: UserId, window: Duration): Boolean

    /** The probing so far in this outage; null before the first probe. */
    suspend fun probe(): ProbeState?

    suspend fun saveProbe(state: ProbeState)
}

/** Tells users' phones that Asha's status changed: the socket if it is open, else a push (the gateway decides). */
fun interface StatusFrames {
    suspend fun send(userIds: Collection<UserId>, status: AshaStatus)
}

/** The health monitor's log lines (the domain doesn't log by itself); never anything of a conversation. */
interface HealthLog {
    /** Asha went down; [reason] is the model's error code. */
    fun down(reason: String)

    /** Asha is back, marked by [by] (`turn` or `probe`), after [downSeconds] when known. */
    fun back(by: String, downSeconds: Long?)

    fun stillDown(failures: Int, nextInSeconds: Long)

    /** [people] heard she is back. */
    fun told(people: Int)

    /** The health store couldn't be read or written; [error] is the exception's class. */
    fun storeFailed(error: String)

    /** Logs nothing: for tests and tools that don't care. */
    object None : HealthLog {
        override fun down(reason: String) = Unit

        override fun back(by: String, downSeconds: Long?) = Unit

        override fun stillDown(failures: Int, nextInSeconds: Long) = Unit

        override fun told(people: Int) = Unit

        override fun storeFailed(error: String) = Unit
    }
}
