package com.circaso.status.domain.usecases

import com.circaso.status.domain.repositories.MediaRepository
import com.circaso.status.domain.support.MomentRemoval
import com.circaso.status.domain.support.StatusConfig
import java.time.Clock

/**
 * The file half of `status.purge-expired` (every 10 minutes, on the leader): deletes every file past its expiry,
 * which is its moment's expiry once attached, now once its moment was deleted, and an hour after ST1 while
 * unattached. Storage first, the row after; a file storage refused keeps its row for the next run. Returns how many
 * files went.
 */
class PurgeMomentMedia(
    private val media: MediaRepository,
    private val removal: MomentRemoval,
    private val config: StatusConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(): Int {
        val now = clock.instant()
        var deleted = 0
        while (true) {
            val batch = media.purgeable(now, config.mediaPurgeBatch)
            val gone = batch.count { removal.deleteFile(it) }
            deleted += gone
            // a short batch is the last; a batch storage refused entirely would only come back, so stop until later
            if (batch.size < config.mediaPurgeBatch || gone == 0) return deleted
        }
    }
}
