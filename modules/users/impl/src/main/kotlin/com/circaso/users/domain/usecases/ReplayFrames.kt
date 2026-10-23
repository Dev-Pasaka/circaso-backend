package com.circaso.users.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.domain.ports.LoggedFrame
import com.circaso.users.domain.ports.RealtimeLog

/** What a phone missed since `seq`: the kept frames, or `null` when the gap is older than the 7 days they are kept. */
class ReplayFrames(private val log: RealtimeLog) {
    suspend operator fun invoke(userId: UserId, seq: Long): Resource<List<LoggedFrame>?> {
        val frames = log.since(userId, seq, LIMIT)
        val latest = log.latest(userId) ?: 0
        val complete = if (frames.isEmpty()) latest <= seq else frames.first().seq == seq + 1
        return Resource.success(frames.takeIf { complete })
    }

    private companion object {
        const val LIMIT = 500
    }
}
