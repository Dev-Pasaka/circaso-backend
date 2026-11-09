package com.circaso.calls.domain.support

import com.circaso.calls.domain.models.Call
import com.circaso.calls.domain.models.EndReason
import com.circaso.calls.domain.models.ParticipantState
import com.circaso.calls.domain.ports.CallFrame
import com.circaso.calls.domain.ports.FrameSender
import com.circaso.integrations.api.CallMedia
import com.circaso.integrations.api.IntegrationError
import com.circaso.shared.EventBus
import com.circaso.shared.EventEnvelope
import com.circaso.shared.Resource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What follows a committed change to a call: everyone in it hears `call.updated` (or `call.ended`), the ended event
 * is published (its outbox row was written with the change), and the SFU closes the tracks of whoever is no longer
 * in it under that session.
 *
 * Closing runs in [background], never on the request (2026-11-09): Cloudflare took 5.5 to 5.9 seconds to answer a
 * close of a session whose phone had already hung up (with `410 Gone`), one session after another, so a hang-up took
 * 6 to 12 seconds to answer while the other phone was told at once; a phone that called again in that window raced
 * its own clean-up. Closing is best effort: a session nobody uses is dropped by the SFU within 30 seconds anyway, so
 * a restart losing a pending close costs nothing and needs no durable job. An SFU that is down is tried [ATTEMPTS]
 * times; anything it refuses (an unknown session) is not tried again.
 */
class CallNotices(
    private val frames: FrameSender,
    private val views: CallViews,
    private val media: CallMedia,
    private val bus: EventBus,
    private val background: CoroutineScope,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun changed(
        before: Call,
        after: Call,
        event: EventEnvelope?,
    ) {
        val everyone = after.participants.map { it.userId }
        val view = views.frameView(after)
        frames.send(everyone, if (after.isEnded) CallFrame.Ended(view) else CallFrame.Updated(view))
        ringStopped(before, after)
        event?.let { bus.publish(it) }
        closeGone(before, after)
    }

    /** Closes the tracks at [mids] of a session given up (its owner left, or a join lost the race), in the background. */
    fun close(sessionId: String, mids: List<String>) {
        if (mids.isEmpty()) return
        background.launch { closeNow(sessionId, mids) }
    }

    private suspend fun closeNow(sessionId: String, mids: List<String>) {
        repeat(ATTEMPTS) { attempt ->
            val result = media.closeTracks(sessionId, mids)
            if (result is Resource.Success) return
            if ((result as Resource.Error).error !is IntegrationError.Unavailable) return
            if (attempt < ATTEMPTS - 1) pause(RETRY_MILLIS * (attempt + 1))
        }
    }

    /**
     * Whoever was still being rung and now isn't, without answering or declining (they are `MISSED`: the caller hung
     * up, the call ended, or their ring ran out), hears `call.ring_stopped` once, so an iPhone's CallKit ring stops.
     */
    private suspend fun ringStopped(before: Call, after: Call) {
        val stopped =
            before.participants
                .filter { it.state == ParticipantState.INVITED && it.userId != before.startedBy }
                .filter { after.participant(it.userId)?.state == ParticipantState.MISSED }
                .map { it.userId }
        if (stopped.isEmpty()) return
        val reason = if (after.isEnded) (after.endReason ?: EndReason.COMPLETED).name else EndReason.MISSED.name
        frames.send(stopped, CallFrame.RingStopped(after.callId, after.chatId, reason))
    }

    private fun closeGone(before: Call, after: Call) {
        before.joined
            .filter { was -> after.participant(was.userId)?.sessionId != was.sessionId }
            .forEach { was -> was.sessionId?.let { close(it, was.tracks.map { track -> track.mid }) } }
    }

    private companion object {
        const val ATTEMPTS = 3
        const val RETRY_MILLIS = 2_000L
    }
}
