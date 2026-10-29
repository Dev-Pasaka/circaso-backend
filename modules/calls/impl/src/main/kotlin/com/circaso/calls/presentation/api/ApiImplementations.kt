package com.circaso.calls.presentation.api

import com.circaso.calls.api.CallFrames
import com.circaso.calls.api.CallId
import com.circaso.calls.api.Calls
import com.circaso.calls.presentation.services.CallEventsService
import com.circaso.messaging.api.ChatId
import com.circaso.shared.CircasoJson
import com.circaso.shared.RealtimeEnvelope
import com.circaso.shared.RealtimeReplay
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.getOrNull

/** calls:api. */
class CallsImpl(private val events: CallEventsService) : Calls {
    override suspend fun liveCall(chatId: ChatId): CallId? = events.liveCall(chatId).getOrNull()
}

/** The gateway's replay after a reconnect: the frames since `seq`, or a `resync` when the gap is older than what is kept. */
class CallsRealtimeReplay(private val events: CallEventsService) : RealtimeReplay {
    override suspend fun since(userId: UserId, seq: Long): Resource<List<RealtimeEnvelope>> {
        val frames =
            events.missedFrames(userId, seq).getOrNull()
                ?: return Resource.success(listOf(RealtimeEnvelope.resync(CallFrames.MODULE, seq)))
        return Resource.success(frames.map { CircasoJson.decodeFromString(RealtimeEnvelope.serializer(), it.json) })
    }
}
