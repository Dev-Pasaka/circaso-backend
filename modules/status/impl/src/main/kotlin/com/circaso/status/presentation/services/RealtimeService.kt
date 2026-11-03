package com.circaso.status.presentation.services

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.status.domain.ports.LoggedFrame
import com.circaso.status.domain.usecases.ReplayFrames

/** The gateway's replay after a reconnect. */
class RealtimeService(private val replayFrames: ReplayFrames) {
    suspend fun missedFrames(userId: UserId, seq: Long): Resource<List<LoggedFrame>?> = replayFrames(userId, seq)
}
