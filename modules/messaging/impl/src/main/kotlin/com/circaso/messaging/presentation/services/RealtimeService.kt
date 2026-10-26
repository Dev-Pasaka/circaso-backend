package com.circaso.messaging.presentation.services

import com.circaso.messaging.domain.ports.LoggedFrame
import com.circaso.messaging.domain.usecases.ReplayFrames
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** The frames a reconnecting phone missed, for the gateway's replay. */
class RealtimeService(private val replayFrames: ReplayFrames) {
    suspend fun missedFrames(userId: UserId, seq: Long): Resource<List<LoggedFrame>?> = replayFrames(userId, seq)
}
