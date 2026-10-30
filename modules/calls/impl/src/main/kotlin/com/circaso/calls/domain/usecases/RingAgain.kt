package com.circaso.calls.domain.usecases

import com.circaso.calls.api.CallId
import com.circaso.calls.domain.errors.CallsError
import com.circaso.calls.domain.models.Call
import com.circaso.calls.domain.models.CallView
import com.circaso.calls.domain.models.ParticipantState
import com.circaso.calls.domain.ports.CallFrame
import com.circaso.calls.domain.ports.FrameSender
import com.circaso.calls.domain.support.CallChanges
import com.circaso.calls.domain.support.CallRules.moved
import com.circaso.calls.domain.support.CallViews
import com.circaso.calls.domain.support.People
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Clock

/**
 * CL9 `POST /calls/{callId}/ring`: someone on a group call rings members who aren't on it (design H2/H4 "Add
 * people"): those who missed, declined or left are `INVITED` again and get `call.ringing`; [userIds] narrows it to
 * some of them, empty means everyone not on the call. A direct call has nobody else to add (`calls.not_a_group`).
 */
class RingAgain(
    private val changes: CallChanges,
    private val views: CallViews,
    private val people: People,
    private val frames: FrameSender,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        userId: UserId,
        callId: CallId,
        userIds: Set<UserId>,
    ): Resource<CallView> {
        var rung = emptyList<UserId>()
        val changed =
            changes.change(callId) { call ->
                ringable(call, userId, userIds).let { targets ->
                    when (targets) {
                        is Resource.Error -> {
                            targets
                        }

                        is Resource.Success -> {
                            rung = targets.data
                            Resource.success(ring(call, targets.data))
                        }
                    }
                }
            }
        return when (changed) {
            is Resource.Error -> {
                changed
            }

            is Resource.Success -> {
                val call = changed.data.after
                if (rung.isNotEmpty()) {
                    frames.send(
                        rung,
                        CallFrame.Ringing(call.callId, call.chatId, call.kind, userId, people.name(userId)),
                    )
                }
                Resource.success(views.view(call))
            }
        }
    }

    private fun ringable(
        call: Call,
        userId: UserId,
        userIds: Set<UserId>,
    ): Resource<List<UserId>> {
        if (call.isEnded) return Resource.error(CallsError.CallEnded)
        if (call.participant(userId)?.state != ParticipantState.JOINED) return Resource.error(CallsError.NotInCall)
        if (!call.isGroup) return Resource.error(CallsError.NotAGroup)
        return Resource.success(
            call.participants
                .filter { it.state in AGAIN && (userIds.isEmpty() || it.userId in userIds) }
                .map { it.userId },
        )
    }

    private fun ring(call: Call, targets: List<UserId>): Call {
        if (targets.isEmpty()) return call
        val now = clock.instant()
        return call.copy(
            participants =
                call.participants.map {
                    if (it.userId in
                        targets
                    ) {
                        it.moved(ParticipantState.INVITED, now)
                    } else {
                        it
                    }
                },
            activeAt = now,
        )
    }

    private companion object {
        val AGAIN = setOf(ParticipantState.MISSED, ParticipantState.DECLINED, ParticipantState.LEFT)
    }
}
