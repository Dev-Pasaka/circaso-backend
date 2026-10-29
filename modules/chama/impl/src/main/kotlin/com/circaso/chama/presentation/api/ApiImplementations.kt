package com.circaso.chama.presentation.api

import com.circaso.chama.api.ChamaFrames
import com.circaso.chama.api.Chamas
import com.circaso.chama.presentation.services.ChamaEventsService
import com.circaso.messaging.api.ChatId
import com.circaso.shared.CircasoJson
import com.circaso.shared.RealtimeEnvelope
import com.circaso.shared.RealtimeReplay
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.getOrNull

/** chama:api. */
class ChamasImpl(private val events: ChamaEventsService) : Chamas {
    override suspend fun hasGoal(chatId: ChatId): Boolean = events.hasGoal(chatId)
}

/** The gateway's replay after a reconnect: the frames since `seq`, or a `resync` when the gap is older than what is kept. */
class ChamaRealtimeReplay(private val events: ChamaEventsService) : RealtimeReplay {
    override suspend fun since(userId: UserId, seq: Long): Resource<List<RealtimeEnvelope>> {
        val frames =
            events.missedFrames(userId, seq).getOrNull()
                ?: return Resource.success(listOf(RealtimeEnvelope.resync(ChamaFrames.MODULE, seq)))
        return Resource.success(frames.map { CircasoJson.decodeFromString(RealtimeEnvelope.serializer(), it.json) })
    }
}
