package com.circaso.calls.domain.usecases

import com.circaso.calls.api.CallId
import com.circaso.calls.domain.errors.CallsError
import com.circaso.calls.domain.models.CallView
import com.circaso.calls.domain.support.CallChanges
import com.circaso.calls.domain.support.CallRules
import com.circaso.calls.domain.support.CallViews
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Clock

/**
 * CL7 `POST /calls/{callId}/leave`: hang up. The SFU closes the leaver's tracks (best effort). The caller hanging up
 * before anyone answered cancels the call (`CANCELLED`); otherwise the call ends (`COMPLETED`) once fewer than two
 * people are left in it. Everyone hears `call.ended` with the final call. Leaving a call that already ended answers
 * it as it ended.
 */
class LeaveCall(private val changes: CallChanges, private val views: CallViews, private val clock: Clock) {
    suspend operator fun invoke(userId: UserId, callId: CallId): Resource<CallView> {
        val changed =
            changes.change(callId) { call ->
                when {
                    call.participant(userId) == null -> Resource.error(CallsError.NotFound)
                    call.isEnded -> Resource.success(call)
                    else -> Resource.success(CallRules.leave(call, userId, clock.instant()))
                }
            }
        return when (changed) {
            is Resource.Error -> changed
            is Resource.Success -> Resource.success(views.view(changed.data.after))
        }
    }
}
