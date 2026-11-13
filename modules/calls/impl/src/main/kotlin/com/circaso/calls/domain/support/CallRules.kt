package com.circaso.calls.domain.support

import com.circaso.calls.api.CallEnded
import com.circaso.calls.domain.models.Call
import com.circaso.calls.domain.models.CallState
import com.circaso.calls.domain.models.EndReason
import com.circaso.calls.domain.models.LeaveCause
import com.circaso.calls.domain.models.Participant
import com.circaso.calls.domain.models.ParticipantState
import com.circaso.shared.UserId
import java.time.Instant

/** How a call moves, as pure functions on the model, so every path (a route, the ring timeout) ends it the same way. */
object CallRules {
    /**
     * Ends the call for [reason]: whoever was in it has left, and whoever it was still ringing missed it (the caller,
     * who never joined their own call, has left).
     */
    fun end(
        call: Call,
        reason: EndReason,
        now: Instant,
    ): Call {
        if (call.isEnded) return call
        val participants =
            call.participants.map { participant ->
                when {
                    participant.isJoined -> participant.moved(ParticipantState.LEFT, now)
                    participant.state != ParticipantState.INVITED -> participant
                    participant.userId == call.startedBy -> participant.moved(ParticipantState.LEFT, now)
                    else -> participant.moved(ParticipantState.MISSED, now)
                }
            }
        return call.copy(
            state = CallState.ENDED,
            endedAt = now,
            endReason = reason,
            activeAt = now,
            participants = participants,
        )
    }

    /**
     * Ends the call if nothing is left of it: an active call with fewer than two people in it is over; a ringing
     * call with nobody still being rung and nobody to talk to ended as missed, declined or cancelled.
     */
    fun settle(call: Call, now: Instant): Call =
        when (call.state) {
            CallState.ENDED -> {
                call
            }

            CallState.ACTIVE -> {
                // A direct call needs both people; a group call goes on while anyone is still in it (Pasaka 2026-11-09).
                val over = if (call.isGroup) call.joined.isEmpty() else call.joined.size < 2
                if (over) end(call, EndReason.COMPLETED, now) else call
            }

            CallState.RINGING -> {
                val ringing = call.participants.any { it.state == ParticipantState.INVITED }
                if (ringing || call.joined.size >= 2) call else end(call, unansweredReason(call), now)
            }
        }

    /**
     * [userId] hung up. The caller hanging up before anyone answered cancels the call. A direct call ends for both
     * the moment either person hangs up (2026-11-09): there is nobody else to talk to, and before this a callee who
     * answered and hung up while the caller's own join was still on its way left the call ringing the caller alone.
     * A group call goes on while anyone is still in it, and ends when the last person leaves (Pasaka 2026-11-09).
     * [cause] is what the phone said (CL7): kept on the leaver's row, and a stall or a reconnect that gave up ends
     * the call `DROPPED` instead of `COMPLETED`.
     */
    fun leave(
        call: Call,
        userId: UserId,
        now: Instant,
        cause: LeaveCause? = null,
    ): Call {
        val left = leaveFor(call, userId, now)
        val withCause =
            if (cause == null) {
                left
            } else {
                left.copy(
                    participants =
                        left.participants.map {
                            if (it.userId ==
                                userId
                            ) {
                                it.copy(leftCause = cause)
                            } else {
                                it
                            }
                        },
                )
            }
        // the phone lost the call rather than hanging up (2026-11-13): staff see it apart from a normal end
        val dropped = cause?.isDrop == true && !call.isEnded && withCause.endReason == EndReason.COMPLETED
        return if (dropped) withCause.copy(endReason = EndReason.DROPPED) else withCause
    }

    private fun leaveFor(
        call: Call,
        userId: UserId,
        now: Instant,
    ): Call {
        val nobodyAnswered = call.joined.none { it.userId != call.startedBy }
        if (userId == call.startedBy && call.state == CallState.RINGING && nobodyAnswered) {
            return end(call, EndReason.CANCELLED, now)
        }
        val gone = call.withParticipant(userId, now) { it.moved(ParticipantState.LEFT, now) }
        if (!call.isGroup) {
            // the callee hanging up a call they never answered is turning it down
            val unanswered = nobodyAnswered && call.participant(userId)?.joinedAt == null
            return end(gone, if (unanswered) EndReason.DECLINED else EndReason.COMPLETED, now)
        }
        return settle(gone, now)
    }

    /** The person's row with a new state; leaving drops their session, its tracks and its connection. */
    fun Participant.moved(state: ParticipantState, now: Instant): Participant =
        if (state == ParticipantState.JOINED) {
            copy(state = state, updatedAt = now, leftCause = null)
        } else {
            copy(
                state = state,
                sessionId = null,
                tracks = emptyList(),
                connectedAt = null,
                screen = false,
                updatedAt = now,
            )
        }

    /** Replaces [userId]'s row (or adds it, for a member who wasn't rung) with what [change] makes of it. */
    fun Call.withParticipant(
        userId: UserId,
        now: Instant,
        change: (Participant) -> Participant,
    ): Call {
        val current = participant(userId)
        val changed = change(current ?: invited(userId, now))
        val participants =
            if (current == null) {
                participants + changed
            } else {
                participants.map { if (it.userId == userId) changed else it }
            }
        return copy(participants = participants, activeAt = now)
    }

    fun invited(userId: UserId, now: Instant): Participant =
        Participant(
            userId = userId,
            state = ParticipantState.INVITED,
            sessionId = null,
            tracks = emptyList(),
            audio = true,
            video = false,
            joinedAt = null,
            updatedAt = now,
        )

    /** The event for a call that just ended; everyone who joined at some point is in it. */
    fun endedEvent(call: Call): CallEnded =
        CallEnded(
            callId = call.callId,
            chatId = call.chatId,
            kind = call.kind.name,
            startedBy = call.startedBy,
            endReason = (call.endReason ?: EndReason.COMPLETED).name,
            joinedUserIds = call.participants.filter { it.joinedAt != null }.map { it.userId },
            createdAt = call.createdAt.toString(),
            startedAt = call.startedAt?.toString(),
            endedAt = (call.endedAt ?: call.activeAt).toString(),
        )

    private fun unansweredReason(call: Call): EndReason {
        val others = call.participants.filter { it.userId != call.startedBy }
        return when {
            others.any { it.state == ParticipantState.MISSED } -> EndReason.MISSED
            others.any { it.state == ParticipantState.DECLINED } -> EndReason.DECLINED
            else -> EndReason.CANCELLED
        }
    }
}
