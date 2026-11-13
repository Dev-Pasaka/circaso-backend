package com.circaso.calls.domain.usecases

import com.circaso.calls.domain.models.Call
import com.circaso.calls.domain.models.EndReason
import com.circaso.calls.domain.models.ParticipantState
import com.circaso.calls.domain.repositories.CallRepository
import com.circaso.calls.domain.support.CallChanges
import com.circaso.calls.domain.support.CallRules
import com.circaso.calls.domain.support.CallRules.moved
import com.circaso.shared.Resource
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * `calls.ring-timeout`, every 15 seconds: whoever a call has rung for 45 seconds without an answer missed it
 * (`MISSED`); a call nobody answered ends `MISSED`, and the callee of a direct call is pushed "Missed call from …".
 * An active call quiet for two hours (no join, leave, pull or media change) is ended `DROPPED` (2026-11-13), in case
 * every phone vanished without hanging up: nobody hung up. How many calls it changed.
 */
class ExpireRings(private val calls: CallRepository, private val changes: CallChanges, private val clock: Clock) {
    suspend operator fun invoke(): Int {
        val now = clock.instant()
        val rung = calls.ringingBefore(now.minus(RING_FOR))
        val idle = calls.idleSince(now.minus(IDLE_FOR))
        val missed = rung.count { changed(changes.change(it) { call -> Resource.success(missed(call, now)) }) }
        val ended =
            idle.count { callId ->
                changed(changes.change(callId) { call -> Resource.success(idle(call, now)) })
            }
        return missed + ended
    }

    private fun changed(result: Resource<CallChanges.Change>): Boolean =
        result is Resource.Success && result.data.before != result.data.after

    private fun missed(call: Call, now: Instant): Call {
        if (call.isEnded) return call
        // Each person's ring is timed from when they were rung, so someone rung again mid-call (CL9) rings in full.
        val cutoff = now.minus(RING_FOR)
        if (call.participants.none {
                it.state == ParticipantState.INVITED &&
                    !it.updatedAt.isAfter(
                        cutoff,
                    )
            }
        ) {
            return call
        }
        val participants =
            call.participants.map { participant ->
                when {
                    participant.state != ParticipantState.INVITED ||
                        participant.updatedAt.isAfter(
                            cutoff,
                        )
                    -> participant

                    participant.userId == call.startedBy -> participant.moved(ParticipantState.LEFT, now)

                    else -> participant.moved(ParticipantState.MISSED, now)
                }
            }
        return CallRules.settle(call.copy(participants = participants), now)
    }

    private fun idle(call: Call, now: Instant): Call {
        if (call.isEnded || call.activeAt.isAfter(now.minus(IDLE_FOR))) return call
        return CallRules.end(call, EndReason.DROPPED, now)
    }

    private companion object {
        val RING_FOR: Duration = Duration.ofSeconds(45)
        val IDLE_FOR: Duration = Duration.ofHours(2)
    }
}
