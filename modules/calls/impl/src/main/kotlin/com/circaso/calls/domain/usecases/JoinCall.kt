package com.circaso.calls.domain.usecases

import com.circaso.calls.domain.errors.CallsError
import com.circaso.calls.domain.models.Call
import com.circaso.calls.domain.models.CallState
import com.circaso.calls.domain.models.CallTrack
import com.circaso.calls.domain.models.JoinCommand
import com.circaso.calls.domain.models.Joined
import com.circaso.calls.domain.models.ParticipantState
import com.circaso.calls.domain.repositories.CallRepository
import com.circaso.calls.domain.support.CallChanges
import com.circaso.calls.domain.support.CallNotices
import com.circaso.calls.domain.support.CallRules.moved
import com.circaso.calls.domain.support.CallRules.withParticipant
import com.circaso.calls.domain.support.CallViews
import com.circaso.calls.domain.support.People
import com.circaso.calls.domain.support.orMediaUnavailable
import com.circaso.integrations.api.CallMedia
import com.circaso.integrations.api.LocalTrack
import com.circaso.shared.Resource
import java.time.Clock

/**
 * CL3 `POST /calls/{callId}/join`: a member's phone joins with its offer. The server opens an SFU session for that
 * connection, pushes the phone's local tracks and hands back the SFU's answer; the session and track names are kept
 * so others can pull them by user (CL4). The second person in makes the call `ACTIVE`. Joining again (the app was
 * restarted) replaces the old session. At most eight people are in a call at once.
 */
class JoinCall(
    private val people: People,
    private val calls: CallRepository,
    private val media: CallMedia,
    private val changes: CallChanges,
    private val notices: CallNotices,
    private val views: CallViews,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: JoinCommand): Resource<Joined> {
        val call = calls.find(command.callId) ?: return Resource.error(CallsError.NotFound)
        val member = people.requireMember(call.chatId, command.userId)
        if (member is Resource.Error) return member
        refusal(call, command)?.let { return Resource.error(it) }

        val sessionId =
            when (val r = media.newSession().orMediaUnavailable()) {
                is Resource.Error -> return r
                is Resource.Success -> r.data
            }
        val local = command.tracks.map { LocalTrack(it.mid, it.trackName) }
        val answer =
            when (val r = media.pushTracks(sessionId, command.offerSdp, local).orMediaUnavailable()) {
                is Resource.Error -> return r
                is Resource.Success -> r.data.answerSdp
            }
        val tracks = command.tracks.map { CallTrack(it.trackName, it.kind, it.mid) }
        val changed = changes.change(command.callId) { current -> joined(current, command, sessionId, tracks) }
        if (changed is Resource.Error) {
            // the call ended or filled up while the SFU was answering: give the new session up
            notices.close(sessionId, tracks.map { it.mid })
            return changed
        }
        val after = (changed as Resource.Success).data.after
        return Resource.success(Joined(sessionId, answer, views.view(after)))
    }

    private fun joined(
        call: Call,
        command: JoinCommand,
        sessionId: String,
        tracks: List<CallTrack>,
    ): Resource<Call> {
        refusal(call, command)?.let { return Resource.error(it) }
        val now = clock.instant()
        val withMe =
            call.withParticipant(command.userId, now) { participant ->
                participant.moved(ParticipantState.JOINED, now).copy(
                    sessionId = sessionId,
                    tracks = tracks,
                    audio = tracks.any { it.kind == AUDIO },
                    video = tracks.any { it.kind == VIDEO },
                    joinedAt = participant.joinedAt ?: now,
                )
            }
        val becameActive = withMe.state == CallState.RINGING && withMe.joined.size >= 2
        return Resource.success(
            if (becameActive) withMe.copy(state = CallState.ACTIVE, startedAt = now) else withMe,
        )
    }

    private fun refusal(call: Call, command: JoinCommand): CallsError? {
        if (call.isEnded) return CallsError.CallEnded
        val others = call.joined.count { it.userId != command.userId }
        return if (others >= Call.MAX_JOINED) CallsError.CallFull else null
    }

    private companion object {
        const val AUDIO = "audio"
        const val VIDEO = "video"
    }
}
