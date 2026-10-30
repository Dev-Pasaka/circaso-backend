package com.circaso.calls.domain.usecases

import com.circaso.calls.domain.models.RenegotiateCommand
import com.circaso.calls.domain.repositories.CallRepository
import com.circaso.calls.domain.support.Sessions
import com.circaso.calls.domain.support.orMediaUnavailable
import com.circaso.integrations.api.CallMedia
import com.circaso.shared.Resource
import java.time.Clock

/** CL5 `POST /calls/{callId}/renegotiate`: the phone's answer to the offer the SFU made in a pull. */
class Renegotiate(
    private val sessions: Sessions,
    private val calls: CallRepository,
    private val media: CallMedia,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: RenegotiateCommand): Resource<Unit> {
        val call =
            when (val r = sessions.own(command.callId, command.userId, command.sessionId)) {
                is Resource.Error -> return r
                is Resource.Success -> r.data.first
            }
        val answered = media.renegotiate(command.sessionId, command.answerSdp).orMediaUnavailable()
        if (answered is Resource.Error) return answered
        calls.touch(call.callId, clock.instant())
        return Resource.success(Unit)
    }
}
