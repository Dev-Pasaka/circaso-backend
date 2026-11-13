package com.circaso.calls.presentation.api

import com.circaso.calls.api.CallFrames
import com.circaso.calls.api.CallId
import com.circaso.calls.api.Calls
import com.circaso.calls.api.CallsAdmin
import com.circaso.calls.api.StaffCall
import com.circaso.calls.api.StaffCallPage
import com.circaso.calls.presentation.mappers.toStaff
import com.circaso.calls.presentation.services.CallAdminService
import com.circaso.calls.presentation.services.CallEventsService
import com.circaso.messaging.api.ChatId
import com.circaso.shared.CircasoJson
import com.circaso.shared.RealtimeEnvelope
import com.circaso.shared.RealtimeReplay
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.getOrNull
import com.circaso.shared.map

/** calls:api. */
class CallsImpl(private val events: CallEventsService) : Calls {
    override suspend fun liveCall(chatId: ChatId): CallId? = events.liveCall(chatId).getOrNull()
}

/** calls:api's staff side, for the ops module (2026-11-13). */
class CallsAdminImpl(private val admin: CallAdminService) : CallsAdmin {
    override suspend fun callsOf(
        userId: UserId,
        cursor: String?,
        limit: Int,
    ): Resource<StaffCallPage> = admin.callsOf(userId, cursor, limit).map { it.toStaff() }

    override suspend fun call(callId: CallId): StaffCall? = admin.call(callId).getOrNull()?.toStaff()
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
