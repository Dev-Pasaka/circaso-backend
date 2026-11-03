package com.circaso.status.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.status.domain.errors.StatusError
import com.circaso.status.domain.models.MediaDownload
import com.circaso.status.domain.models.MediaState
import com.circaso.status.domain.models.MomentMedia
import com.circaso.status.domain.ports.MediaStorage
import com.circaso.status.domain.repositories.MediaRepository
import com.circaso.status.domain.repositories.MomentRepository
import com.circaso.status.domain.support.MomentReach
import com.circaso.status.domain.support.StatusConfig
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * ST8 `GET /moments/media/{mediaId}`: a 15-minute download link for the poster, or for anyone in the audience of the
 * live moment carrying the file (and not blocked either way since). Everything else, a pending or expired file
 * included, answers `status.media_not_found`, so nobody learns that it exists.
 */
class GetMomentMediaDownload(
    private val media: MediaRepository,
    private val moments: MomentRepository,
    private val reach: MomentReach,
    private val storage: MediaStorage,
    private val config: StatusConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(caller: UserId, mediaId: UUID): Resource<MediaDownload> {
        val now = clock.instant()
        val file = media.find(mediaId)
        if (file == null || !mayDownload(caller, file, now)) return Resource.error(StatusError.MediaNotFound)

        val url =
            when (val signed = storage.prepareDownload(file.posterId, mediaId, config.mediaUrlTtl)) {
                is Resource.Success -> signed.data
                is Resource.Error -> return signed
            }
        return Resource.success(MediaDownload(url, now.plus(config.mediaUrlTtl), file.sizeBytes))
    }

    private suspend fun mayDownload(
        caller: UserId,
        file: MomentMedia,
        now: Instant,
    ): Boolean {
        if (file.state != MediaState.READY || !file.expiresAt.isAfter(now)) return false
        if (file.posterId == caller) return true
        val momentId = file.momentId ?: return false
        val moment = moments.findLive(momentId, now) ?: return false
        return moments.member(momentId, caller) != null && reach.stillVisible(caller, moment.posterId)
    }
}
