package com.circaso.calls.domain.support

import com.circaso.calls.api.CallId
import com.circaso.calls.domain.errors.CallsError
import com.circaso.calls.domain.models.Call
import com.circaso.calls.domain.models.Participant
import com.circaso.calls.domain.repositories.CallRepository
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** The checks before a joined participant's session talks to the SFU: in the call, and with their own session. */
class Sessions(private val calls: CallRepository) {
    suspend fun own(
        callId: CallId,
        userId: UserId,
        sessionId: String,
    ): Resource<Pair<Call, Participant>> {
        val call = calls.find(callId) ?: return Resource.error(CallsError.NotFound)
        val me = call.participant(userId) ?: return Resource.error(CallsError.NotFound)
        if (call.isEnded) return Resource.error(CallsError.CallEnded)
        if (!me.isJoined) return Resource.error(CallsError.NotInCall)
        if (me.sessionId != sessionId) return Resource.error(CallsError.SessionMismatch)
        return Resource.success(call to me)
    }
}
