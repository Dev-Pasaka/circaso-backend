package com.circaso.calls.domain.usecases

import com.circaso.calls.api.CallId
import com.circaso.calls.domain.errors.CallsError
import com.circaso.calls.domain.models.Call
import com.circaso.calls.domain.models.CallView
import com.circaso.calls.domain.models.EndReason
import com.circaso.calls.domain.models.ParticipantState
import com.circaso.calls.domain.support.CallChanges
import com.circaso.calls.domain.support.CallRules
import com.circaso.calls.domain.support.CallRules.moved
import com.circaso.calls.domain.support.CallRules.withParticipant
import com.circaso.calls.domain.support.CallViews
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Clock

/**
 * CL6 `POST /calls/{callId}/decline`: the person rung says no. In a direct call that ends it (`DECLINED`); in a
 * group only they are `DECLINED`, and the call ends only when nobody is left to ring or talk to. Declining what is
 * no longer ringing you changes nothing; the caller declining their own call is leaving it (CL7).
 */
class DeclineCall(private val changes: CallChanges, private val views: CallViews, private val clock: Clock) {
    suspend operator fun invoke(userId: UserId, callId: CallId): Resource<CallView> {
        val changed = changes.change(callId) { call -> declined(call, userId) }
        return when (changed) {
            is Resource.Error -> changed
            is Resource.Success -> Resource.success(views.view(changed.data.after))
        }
    }

    private fun declined(call: Call, userId: UserId): Resource<Call> {
        val me = call.participant(userId) ?: return Resource.error(CallsError.NotFound)
        if (call.isEnded) return Resource.error(CallsError.CallEnded)
        val now = clock.instant()
        if (userId == call.startedBy) return Resource.success(CallRules.leave(call, userId, now))
        if (me.state != ParticipantState.INVITED) return Resource.success(call)
        val declined = call.withParticipant(userId, now) { it.moved(ParticipantState.DECLINED, now) }
        if (!call.isGroup) return Resource.success(CallRules.end(declined, EndReason.DECLINED, now))
        return Resource.success(CallRules.settle(declined, now))
    }
}
