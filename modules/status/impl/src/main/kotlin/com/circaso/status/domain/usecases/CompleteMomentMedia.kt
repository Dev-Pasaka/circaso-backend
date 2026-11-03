package com.circaso.status.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.status.domain.errors.StatusError
import com.circaso.status.domain.models.MediaState
import com.circaso.status.domain.models.MomentMedia
import com.circaso.status.domain.ports.MediaStorage
import com.circaso.status.domain.ports.Transactor
import com.circaso.status.domain.repositories.MediaRepository
import java.time.Clock
import java.util.UUID

/**
 * ST2 `POST /moments/media/{mediaId}/complete`: the poster says the PUT is done. The object must be in storage at
 * exactly ST1's size (`status.media_invalid` otherwise), then the row is `READY`. Completing a ready file again
 * answers the same, so a retry is harmless.
 */
class CompleteMomentMedia(
    private val media: MediaRepository,
    private val transactor: Transactor,
    private val storage: MediaStorage,
    private val clock: Clock,
) {
    suspend operator fun invoke(caller: UserId, mediaId: UUID): Resource<MomentMedia> {
        val file = media.find(mediaId)
        if (file == null || file.posterId != caller || !file.expiresAt.isAfter(clock.instant())) {
            return Resource.error(StatusError.MediaNotFound)
        }
        if (file.state == MediaState.READY) return Resource.success(file)

        val stored = storage.sizeOf(file.posterId, mediaId)
        if (stored != file.sizeBytes) return Resource.error(StatusError.MediaInvalid)

        val ready = transactor.run("media.complete") { media.markReady(mediaId) }
        if (!ready && media.find(mediaId)?.state != MediaState.READY) return Resource.error(StatusError.MediaNotFound)
        return Resource.success(file.copy(state = MediaState.READY))
    }
}
