package com.circaso.calls.presentation.services

import com.circaso.calls.domain.models.HeartbeatCommand
import com.circaso.calls.domain.usecases.RelayHeartbeat
import com.circaso.shared.Resource

/** The frames a phone in a call sends over the socket: `calls/heartbeat` (2026-11-13). */
class CallPresenceService(private val relayHeartbeat: RelayHeartbeat) {
    suspend fun heartbeat(command: HeartbeatCommand): Resource<Unit> = relayHeartbeat(command)
}
