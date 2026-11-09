package com.circaso.chama.domain.usecases

import com.circaso.chama.domain.ports.LoggedFrame
import com.circaso.chama.domain.ports.RealtimeLog
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/**
 * The gateway's replay after a reconnect: the frames since [seq], or null (a `resync`) when the phone's number can't be
 * trusted: the gap is older than what is kept, or the member's counter started again below it (2026-11-09).
 *
 * The counter (`cha:seq:{userId}`) lives 8 days after the member's last frame. Chama frames are rare, so a quiet week
 * ends it and the next frame is 1 again, while the phone still holds, say, 40: it took every new frame for one it had
 * already seen and dropped it, so a contribution's `money.updated` never moved anyone's balance. A resync makes the
 * phone forget its number and reload the group's money over HTTP.
 */
class ReplayFrames(private val log: RealtimeLog) {
    suspend operator fun invoke(userId: UserId, seq: Long): Resource<List<LoggedFrame>?> {
        val frames = log.since(userId, seq, LIMIT)
        if (frames.isEmpty() && seq > 0) {
            val latest = log.latest(userId)
            // Nothing missed only when the counter stands exactly where the phone is.
            if (latest != seq) return Resource.success(null)
        }
        return Resource.success(frames)
    }

    private companion object {
        const val LIMIT = 500
    }
}
