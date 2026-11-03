package com.circaso.status.domain.support

import com.circaso.status.api.MomentDeletedFrame
import com.circaso.status.domain.models.Moment
import com.circaso.status.domain.models.MomentMedia
import com.circaso.status.domain.ports.FrameSender
import com.circaso.status.domain.ports.MediaStorage
import com.circaso.status.domain.ports.Transactor
import com.circaso.status.domain.repositories.MediaRepository
import com.circaso.status.domain.repositories.MomentRepository
import java.time.Clock

/**
 * Removing a moment before it expires, for ST7 and for a deleted account: the moment and its audience go in one
 * transaction, which also brings its file's expiry forward to now; then the audience gets `status.deleted` and the
 * file is deleted from storage and its row after it. A file storage couldn't delete stays expired, so the next sweep
 * tries again.
 */
class MomentRemoval(
    private val moments: MomentRepository,
    private val media: MediaRepository,
    private val transactor: Transactor,
    private val storage: MediaStorage,
    private val frames: FrameSender,
    private val clock: Clock,
) {
    /** False when the moment was already gone. */
    suspend fun remove(moment: Moment): Boolean {
        val now = clock.instant()
        val removed =
            transactor.run("moments.delete") {
                val audience = moments.audienceOf(moment.momentId)
                if (!moments.delete(moment.momentId)) return@run null
                audience to media.expireFor(moment.momentId, now)
            } ?: return false

        val (audience, files) = removed
        if (audience.isNotEmpty()) frames.send(audience, MomentDeletedFrame(moment.momentId, moment.posterId))
        files.forEach { deleteFile(it) }
        return true
    }

    /** The object first, then the row, so a row is never lost while its blob stays. */
    suspend fun deleteFile(file: MomentMedia): Boolean {
        if (!storage.delete(file.posterId, file.mediaId)) return false
        return transactor.run("media.delete") { media.delete(file.mediaId) }
    }
}
