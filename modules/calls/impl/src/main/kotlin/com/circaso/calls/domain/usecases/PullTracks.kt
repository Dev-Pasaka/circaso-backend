package com.circaso.calls.domain.usecases

import com.circaso.calls.domain.models.PullCommand
import com.circaso.calls.domain.models.Pulled
import com.circaso.calls.domain.models.PulledFrom
import com.circaso.calls.domain.repositories.CallRepository
import com.circaso.calls.domain.support.Sessions
import com.circaso.calls.domain.support.orMediaUnavailable
import com.circaso.integrations.api.CallMedia
import com.circaso.integrations.api.RemoteTrack
import com.circaso.shared.Resource
import java.time.Clock

/**
 * CL4 `POST /calls/{callId}/pull`: the phone asks for other people's tracks by user and track name; the server maps
 * each person to their SFU session (which the phone never sees) and asks the SFU to send them. A track of someone
 * no longer in the call, or one they don't publish, is left out. When the SFU offers back, the phone answers (CL5).
 */
class PullTracks(
    private val sessions: Sessions,
    private val calls: CallRepository,
    private val media: CallMedia,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: PullCommand): Resource<Pulled> {
        val call =
            when (val r = sessions.own(command.callId, command.userId, command.sessionId)) {
                is Resource.Error -> return r
                is Resource.Success -> r.data.first
            }
        val publishers = call.joined.filter { it.userId != command.userId }.associateBy { it.userId }
        val wanted =
            command.tracks.mapNotNull { want ->
                val publisher = publishers[want.userId] ?: return@mapNotNull null
                val session = publisher.sessionId ?: return@mapNotNull null
                if (publisher.tracks.none { it.trackName == want.trackName }) return@mapNotNull null
                RemoteTrack(session, want.trackName)
            }
        if (wanted.isEmpty()) return Resource.success(Pulled(null, false, emptyList()))

        val pulled =
            when (val r = media.pullTracks(command.sessionId, wanted).orMediaUnavailable()) {
                is Resource.Error -> return r
                is Resource.Success -> r.data
            }
        calls.touch(call.callId, clock.instant())
        val owners = publishers.values.associate { it.sessionId to it.userId }
        val tracks =
            pulled.tracks.mapNotNull { track ->
                val owner = owners[track.sessionId] ?: return@mapNotNull null
                val mid = track.mid ?: return@mapNotNull null
                PulledFrom(owner, track.trackName, mid)
            }
        return Resource.success(Pulled(pulled.offerSdp, pulled.requiresRenegotiation, tracks))
    }
}
