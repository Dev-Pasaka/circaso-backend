package com.circaso.status.data.storage

import com.circaso.infrastructure.api.ObjectStore
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.status.domain.models.MEDIA_CONTENT_TYPE
import com.circaso.status.domain.ports.MediaStorage
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID

private val log = LoggerFactory.getLogger("com.circaso.status.data")

/** Moment files in status' prefix: `media/{posterId}/{mediaId}`, always ciphertext. */
class ObjectStoreMomentMedia(private val store: ObjectStore) : MediaStorage {
    private fun key(posterId: UserId, mediaId: UUID) = "media/$posterId/$mediaId"

    override suspend fun prepareUpload(
        posterId: UserId,
        mediaId: UUID,
        expiresIn: Duration,
    ): Resource<String> = store.presignUpload(key(posterId, mediaId), MEDIA_CONTENT_TYPE, expiresIn)

    override suspend fun sizeOf(posterId: UserId, mediaId: UUID): Long? =
        store.describe(key(posterId, mediaId))?.sizeBytes

    override suspend fun prepareDownload(
        posterId: UserId,
        mediaId: UUID,
        expiresIn: Duration,
    ): Resource<String> = store.presignDownload(key(posterId, mediaId), expiresIn)

    /** Deleting a key that isn't there succeeds; a storage failure is logged by its class (never the key) and kept. */
    override suspend fun delete(posterId: UserId, mediaId: UUID): Boolean =
        try {
            store.delete(key(posterId, mediaId))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("could not delete a moment file: error={}", e.javaClass.name)
            false
        }
}
