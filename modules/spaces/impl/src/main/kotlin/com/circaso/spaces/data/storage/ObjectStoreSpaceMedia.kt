package com.circaso.spaces.data.storage

import com.circaso.infrastructure.api.ObjectStore
import com.circaso.shared.Resource
import com.circaso.shared.getOrNull
import com.circaso.spaces.domain.ports.MediaStorage
import org.slf4j.LoggerFactory
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException

private val log = LoggerFactory.getLogger("com.circaso.spaces.data")

/** Space files in spaces' prefix (`media/{mediaId}`), as uploaded: space content isn't end to end encrypted. */
class ObjectStoreSpaceMedia(private val store: ObjectStore) : MediaStorage {
    override suspend fun prepareUpload(
        key: String,
        contentType: String,
        expiresIn: Duration,
    ): Resource<String> = store.presignUpload(key, contentType, expiresIn)

    override suspend fun sizeOf(key: String): Long? = store.describe(key)?.sizeBytes

    override suspend fun downloadUrl(key: String, expiresIn: Duration): String? =
        store.presignDownload(key, expiresIn).getOrNull()

    /** A storage failure is logged by its class (never the key) and left for the next sweep. */
    override suspend fun delete(key: String) {
        try {
            store.delete(key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("could not delete a space file: error={}", e.javaClass.name)
        }
    }
}
