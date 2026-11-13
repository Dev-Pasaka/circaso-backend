package com.circaso.calls.presentation.api

import com.circaso.calls.domain.models.HeartbeatCommand
import com.circaso.calls.presentation.dto.HeartbeatFrameDto
import com.circaso.calls.presentation.mappers.callIdOf
import com.circaso.calls.presentation.services.CallPresenceService
import com.circaso.shared.ClientFrame
import com.circaso.shared.DeviceId
import com.circaso.shared.RealtimeInbound
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.shared.decodeData
import com.circaso.shared.flatMap

/**
 * The phone's `calls` frames, routed here by the socket hub: only `heartbeat` `{ callId }` (2026-11-13), which says
 * "my phone is still on this call" every 2 seconds. Anything else answers `request.malformed`, which the hub only
 * logs: the socket stays open.
 */
class CallsRealtimeInbound(private val presence: CallPresenceService) : RealtimeInbound {
    override suspend fun receive(
        userId: UserId,
        deviceId: DeviceId,
        frame: ClientFrame,
    ): Resource<Unit> {
        if (frame.type != HEARTBEAT) return Resource.error(SharedError.Malformed("type", "is not a calls frame"))
        val data =
            runCatching { frame.decodeData(HeartbeatFrameDto.serializer()) }.getOrNull()
                ?: return Resource.error(SharedError.Malformed("data", "is not a heartbeat"))
        return callIdOf(data.callId).flatMap { presence.heartbeat(HeartbeatCommand(userId, it)) }
    }

    private companion object {
        const val HEARTBEAT = "heartbeat"
    }
}
