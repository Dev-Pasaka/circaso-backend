package com.circaso.messaging.domain.ports

import com.circaso.messaging.api.ChatId
import com.circaso.shared.Resource
import java.time.Duration
import java.util.UUID

/**
 * Attachment blobs in object storage, under messaging's prefix (`attachments/{chatId}/{attachmentId}`). Bytes go
 * straight between the phone and storage through pre-signed URLs; the backend only checks the size afterwards.
 */
interface AttachmentStorage {
    /** A URL the phone can PUT the ciphertext to, with `Content-Type: application/octet-stream`, until it expires. */
    suspend fun prepareUpload(
        chatId: ChatId,
        attachmentId: UUID,
        expiresIn: Duration,
    ): Resource<String>

    /** The size of what was uploaded, or null when nothing is there. */
    suspend fun sizeOf(chatId: ChatId, attachmentId: UUID): Long?

    suspend fun prepareDownload(
        chatId: ChatId,
        attachmentId: UUID,
        expiresIn: Duration,
    ): Resource<String>

    /** True once the object is gone (or was never there); false when storage could not be reached. */
    suspend fun delete(chatId: ChatId, attachmentId: UUID): Boolean
}
