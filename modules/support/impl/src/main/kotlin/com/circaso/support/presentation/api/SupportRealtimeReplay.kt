package com.circaso.support.presentation.api

import com.circaso.shared.CircasoJson
import com.circaso.shared.RealtimeEnvelope
import com.circaso.shared.RealtimeReplay
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.getOrNull
import com.circaso.support.api.SupportFrames
import com.circaso.support.presentation.services.RealtimeService

/**
 * What the gateway calls after a reconnect with the app's last support `seq`: the kept frames it missed, or a single
 * `resync` frame when the gap is older than the 7 days they are kept, after which the app reloads SU2.
 */
class SupportRealtimeReplay(private val realtime: RealtimeService) : RealtimeReplay {
    override suspend fun since(userId: UserId, seq: Long): Resource<List<RealtimeEnvelope>> {
        val frames =
            realtime.missedFrames(userId, seq).getOrNull()
                ?: return Resource.success(listOf(RealtimeEnvelope.resync(SupportFrames.MODULE, seq)))
        return Resource.success(frames.map { CircasoJson.decodeFromString(RealtimeEnvelope.serializer(), it.json) })
    }
}
