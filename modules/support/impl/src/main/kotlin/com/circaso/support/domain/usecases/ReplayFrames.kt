package com.circaso.support.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.support.domain.ports.LoggedFrame
import com.circaso.support.domain.ports.RealtimeLog

/** The gateway's replay after a reconnect: the frames since [seq], or null when the gap is older than what is kept. */
class ReplayFrames(private val log: RealtimeLog) {
    suspend operator fun invoke(userId: UserId, seq: Long): Resource<List<LoggedFrame>?> {
        val frames = log.since(userId, seq, LIMIT)
        if (frames.isEmpty()) {
            val latest = log.latest(userId) ?: return Resource.success(emptyList())
            if (latest > seq) return Resource.success(null) // frames existed after seq but are gone: resync
        }
        return Resource.success(frames)
    }

    private companion object {
        const val LIMIT = 500
    }
}
