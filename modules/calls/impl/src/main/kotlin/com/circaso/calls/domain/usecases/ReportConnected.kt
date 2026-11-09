package com.circaso.calls.domain.usecases

import com.circaso.calls.domain.errors.CallsError
import com.circaso.calls.domain.models.Call
import com.circaso.calls.domain.models.ConnectedCommand
import com.circaso.calls.domain.support.CallChanges
import com.circaso.calls.domain.support.CallRules.withParticipant
import com.circaso.shared.Resource
import java.time.Clock

/**
 * CL3b `POST /calls/{callId}/connected`: the phone's peer connection under its session is `connected`, so its
 * tracks are ready to be pulled (2026-11-09). Everyone hears it in `call.updated` (`connected: true` on the tile) and
 * pulls this person then. Cloudflare's order is publish, wait for `connected`, then make the publication
 * discoverable: a pull sent before that is held by the SFU for about 9 seconds, which is why phones heard each other
 * only 9 seconds after answering. Saying it again for the same session changes nothing; an older session is refused.
 */
class ReportConnected(private val changes: CallChanges, private val clock: Clock) {
    suspend operator fun invoke(command: ConnectedCommand): Resource<Unit> {
        val changed = changes.change(command.callId) { call -> connected(call, command) }
        return if (changed is Resource.Error) changed else Resource.success(Unit)
    }

    private fun connected(call: Call, command: ConnectedCommand): Resource<Call> {
        val me = call.participant(command.userId) ?: return Resource.error(CallsError.NotFound)
        if (call.isEnded) return Resource.error(CallsError.CallEnded)
        if (!me.isJoined) return Resource.error(CallsError.NotInCall)
        if (me.sessionId != command.sessionId) return Resource.error(CallsError.SessionMismatch)
        if (me.connectedAt != null) return Resource.success(call)
        val now = clock.instant()
        return Resource.success(
            call.withParticipant(command.userId, now) { it.copy(connectedAt = now, updatedAt = now) },
        )
    }
}
