package com.circaso.calls.domain.usecases

import com.circaso.calls.domain.errors.CallsError
import com.circaso.calls.domain.models.Call
import com.circaso.calls.domain.models.MediaCommand
import com.circaso.calls.domain.support.CallChanges
import com.circaso.calls.domain.support.CallRules.withParticipant
import com.circaso.shared.Resource
import java.time.Clock

/**
 * CL8 `POST /calls/{callId}/media`: the microphone muted or the camera turned off, so the others' tiles show it
 * (`call.updated`). The tracks stay published; the phone stops sending on them.
 */
class SetMedia(private val changes: CallChanges, private val clock: Clock) {
    suspend operator fun invoke(command: MediaCommand): Resource<Unit> {
        val changed = changes.change(command.callId) { call -> withMedia(call, command) }
        return if (changed is Resource.Error) changed else Resource.success(Unit)
    }

    private fun withMedia(call: Call, command: MediaCommand): Resource<Call> {
        val me = call.participant(command.userId) ?: return Resource.error(CallsError.NotFound)
        if (call.isEnded) return Resource.error(CallsError.CallEnded)
        if (!me.isJoined) return Resource.error(CallsError.NotInCall)
        val now = clock.instant()
        return Resource.success(
            call.withParticipant(command.userId, now) {
                it.copy(audio = command.audio, video = command.video, updatedAt = now)
            },
        )
    }
}
