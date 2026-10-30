package com.circaso.messaging.data.storage

import com.circaso.infrastructure.api.ObjectStore
import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.models.ATTACHMENT_CONTENT_TYPE
import com.circaso.messaging.domain.ports.AttachmentStorage
import com.circaso.shared.Resource
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID

private val log = LoggerFactory.getLogger("com.circaso.messaging.data")

/** Attachments in messaging's file prefix: `attachments/{chatId}/{attachmentId}`, always ciphertext. */
class ObjectStoreAttachments(private val store: ObjectStore) : AttachmentStorage {
    private fun key(chatId: ChatId, attachmentId: UUID) = "attachments/$chatId/$attachmentId"

    override suspend fun prepareUpload(
        chatId: ChatId,
        attachmentId: UUID,
        expiresIn: Duration,
    ): Resource<String> = store.presignUpload(key(chatId, attachmentId), ATTACHMENT_CONTENT_TYPE, expiresIn)

    override suspend fun sizeOf(chatId: ChatId, attachmentId: UUID): Long? =
        store.describe(key(chatId, attachmentId))?.sizeBytes

    override suspend fun prepareDownload(
        chatId: ChatId,
        attachmentId: UUID,
        expiresIn: Duration,
    ): Resource<String> = store.presignDownload(key(chatId, attachmentId), expiresIn)

    /** Deleting a key that isn't there succeeds; a storage failure is logged by its class (never the key) and kept. */
    override suspend fun delete(chatId: ChatId, attachmentId: UUID): Boolean =
        try {
            store.delete(key(chatId, attachmentId))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("could not delete an attachment: error={}", e.javaClass.name)
            false
        }
}
