package com.circaso.status.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.Uuid7
import com.circaso.status.domain.errors.StatusError
import com.circaso.status.domain.models.MEDIA_CONTENT_TYPE
import com.circaso.status.domain.models.MediaState
import com.circaso.status.domain.models.MediaUpload
import com.circaso.status.domain.models.MomentMedia
import com.circaso.status.domain.models.PrepareMediaCommand
import com.circaso.status.domain.ports.MediaStorage
import com.circaso.status.domain.ports.Transactor
import com.circaso.status.domain.repositories.MediaRepository
import com.circaso.status.domain.support.StatusConfig
import java.time.Clock

/**
 * ST1 `POST /moments/media`: a pre-signed PUT for one encrypted file and its `PENDING` row. The URL is signed before
 * the row is written, so a storage outage leaves nothing behind; a file not attached to a moment within an hour is
 * deleted by the sweep.
 */
class PrepareMomentMedia(
    private val media: MediaRepository,
    private val transactor: Transactor,
    private val storage: MediaStorage,
    private val config: StatusConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: PrepareMediaCommand): Resource<MediaUpload> {
        if (command.sizeBytes !in 1..config.mediaMaxBytes) return Resource.error(StatusError.MediaInvalid)

        val now = clock.instant()
        val mediaId = Uuid7.generate()
        val url =
            when (val signed = storage.prepareUpload(command.poster, mediaId, config.mediaUrlTtl)) {
                is Resource.Success -> signed.data
                is Resource.Error -> return signed
            }
        val row =
            MomentMedia(
                mediaId = mediaId,
                posterId = command.poster,
                kind = command.kind,
                sizeBytes = command.sizeBytes,
                state = MediaState.PENDING,
                momentId = null,
                createdAt = now,
                expiresAt = now.plus(config.unattachedMediaTtl),
            )
        transactor.run("media.prepare") { media.insert(row) }

        return Resource.success(MediaUpload(mediaId, url, MEDIA_CONTENT_TYPE, now.plus(config.mediaUrlTtl)))
    }
}
