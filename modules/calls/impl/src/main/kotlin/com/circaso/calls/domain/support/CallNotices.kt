package com.circaso.calls.domain.support

import com.circaso.calls.domain.models.Call
import com.circaso.calls.domain.ports.CallFrame
import com.circaso.calls.domain.ports.FrameSender
import com.circaso.integrations.api.CallMedia
import com.circaso.shared.EventBus
import com.circaso.shared.EventEnvelope

/**
 * What follows a committed change to a call: everyone in it hears `call.updated` (or `call.ended`), the ended event
 * is published (its outbox row was written with the change), and the SFU closes the tracks of whoever is no longer
 * in it under that session. Closing is best effort: a session nobody uses times out at the SFU anyway.
 */
class CallNotices(
    private val frames: FrameSender,
    private val views: CallViews,
    private val media: CallMedia,
    private val bus: EventBus,
) {
    suspend fun changed(
        before: Call,
        after: Call,
        event: EventEnvelope?,
    ) {
        val everyone = after.participants.map { it.userId }
        val view = views.frameView(after)
        frames.send(everyone, if (after.isEnded) CallFrame.Ended(view) else CallFrame.Updated(view))
        event?.let { bus.publish(it) }
        closeGone(before, after)
    }

    /** Closes the tracks at [mids] of a session given up (its owner left, or a join lost the race). */
    suspend fun close(sessionId: String, mids: List<String>) {
        media.closeTracks(sessionId, mids)
    }

    private suspend fun closeGone(before: Call, after: Call) {
        before.joined
            .filter { was -> after.participant(was.userId)?.sessionId != was.sessionId }
            .forEach { was -> was.sessionId?.let { close(it, was.tracks.map { track -> track.mid }) } }
    }
}
