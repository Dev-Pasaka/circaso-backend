package com.circaso.spaces.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import com.circaso.spaces.domain.errors.SpacesError
import com.circaso.spaces.domain.models.MediaKind
import com.circaso.spaces.domain.models.MediaPurpose
import com.circaso.spaces.domain.models.MediaState
import com.circaso.spaces.domain.models.MediaUpload
import com.circaso.spaces.domain.models.PrepareMediaCommand
import com.circaso.spaces.domain.models.SpaceMedia
import com.circaso.spaces.domain.ports.MediaStorage
import com.circaso.spaces.domain.repositories.MediaRepository
import com.circaso.spaces.domain.support.SpacesConfig
import com.circaso.spaces.domain.support.mediaKey
import java.time.Clock
import java.util.UUID

/**
 * SP33: a pre-signed upload for a space photo, cover or post file (not encrypted: space content isn't end to end).
 * Photos and covers are images; sizes are capped per kind; the content type must match the kind.
 */
class PrepareSpaceMedia(
    private val media: MediaRepository,
    private val storage: MediaStorage,
    private val config: SpacesConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: PrepareMediaCommand): Resource<MediaUpload> {
        if (!acceptable(command)) return Resource.error(SpacesError.MediaInvalid)
        val now = clock.instant()
        val file =
            SpaceMedia(
                mediaId = Uuid7.generate(),
                ownerId = command.ownerId,
                purpose = command.purpose,
                kind = command.kind,
                contentType = command.contentType,
                sizeBytes = command.sizeBytes,
                width = command.width,
                height = command.height,
                durationMs = command.durationMs,
                state = MediaState.PENDING,
                createdAt = now,
                expiresAt = now.plus(config.mediaUploadWindow),
            )
        val url =
            when (val signed = storage.prepareUpload(mediaKey(file.mediaId), file.contentType, config.mediaUploadWindow)) {
                is Resource.Success -> signed.data
                is Resource.Error -> return signed
            }
        media.insert(file)
        return Resource.success(MediaUpload(file.mediaId, url, file.contentType, file.expiresAt))
    }

    private fun acceptable(command: PrepareMediaCommand): Boolean {
        val max =
            when (command.kind) {
                MediaKind.IMAGE -> config.imageMaxBytes
                MediaKind.VIDEO -> config.videoMaxBytes
                MediaKind.VOICE -> config.voiceMaxBytes
            }
        val family =
            when (command.kind) {
                MediaKind.IMAGE -> "image/"
                MediaKind.VIDEO -> "video/"
                MediaKind.VOICE -> "audio/"
            }
        val imageOnly = command.purpose != MediaPurpose.POST && command.kind != MediaKind.IMAGE
        return !imageOnly && command.sizeBytes in 1..max && command.contentType.startsWith(family) &&
            command.contentType.length <= CONTENT_TYPE_MAX
    }

    private companion object {
        const val CONTENT_TYPE_MAX = 100
    }
}

/** SP34: checks the uploaded object is there at the promised size; then the file can be used once. */
class CompleteSpaceMedia(
    private val media: MediaRepository,
    private val storage: MediaStorage,
    private val clock: Clock,
) {
    suspend operator fun invoke(caller: UserId, mediaId: UUID): Resource<SpaceMedia> {
        val file = media.find(mediaId)?.takeIf { it.ownerId == caller } ?: return Resource.error(SpacesError.MediaNotFound)
        if (file.state != MediaState.PENDING) return Resource.success(file)
        if (clock.instant() >= file.expiresAt) return Resource.error(SpacesError.MediaNotFound)
        val size = storage.sizeOf(mediaKey(mediaId)) ?: return Resource.error(SpacesError.MediaNotFound)
        if (size != file.sizeBytes) return Resource.error(SpacesError.MediaInvalid)
        media.markReady(mediaId, size)
        return Resource.success(file.copy(state = MediaState.READY))
    }
}

/** `spaces.purge-media`: files uploaded but never used within their window leave storage and the table. */
class PurgeSpaceMedia(
    private val media: MediaRepository,
    private val storage: MediaStorage,
    private val clock: Clock,
) {
    suspend operator fun invoke(): Resource<Int> {
        val expired = media.expired(clock.instant(), BATCH)
        expired.forEach {
            storage.delete(mediaKey(it.mediaId))
            media.delete(it.mediaId)
        }
        return Resource.success(expired.size)
    }

    private companion object {
        const val BATCH = 500
    }
}
