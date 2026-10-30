package com.circaso.messaging

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.models.Attachment
import com.circaso.messaging.domain.models.AttachmentState
import com.circaso.messaging.domain.ports.AttachmentStorage
import com.circaso.messaging.domain.repositories.AttachmentRepository
import com.circaso.shared.Resource
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** `attachments` in memory, with the same conditions as the SQL. */
class MemoryAttachments : AttachmentRepository {
    val rows = ConcurrentHashMap<UUID, Attachment>()

    override suspend fun insert(attachment: Attachment) {
        rows[attachment.attachmentId] = attachment
    }

    override suspend fun find(attachmentId: UUID): Attachment? = rows[attachmentId]

    override suspend fun markReady(attachmentId: UUID): Boolean {
        val row = rows[attachmentId]?.takeIf { it.state == AttachmentState.PENDING } ?: return false
        rows[attachmentId] = row.copy(state = AttachmentState.READY)
        return true
    }

    override suspend fun purgeable(
        now: Instant,
        pendingBefore: Instant,
        limit: Int,
    ): List<Attachment> =
        rows.values
            .filter {
                !it.expiresAt.isAfter(now) ||
                    (it.state == AttachmentState.PENDING && !it.createdAt.isAfter(pendingBefore))
            }.sortedBy { it.createdAt }
            .take(limit)

    override suspend fun delete(attachmentId: UUID): Boolean = rows.remove(attachmentId) != null
}

/** Object storage in memory: [put] stands in for the phone's PUT; [failing] makes every delete fail. */
class MemoryAttachmentStorage : AttachmentStorage {
    val objects = ConcurrentHashMap<String, Long>()
    val signedUploads = mutableListOf<Pair<String, Duration>>()
    var failing = false

    private fun key(chatId: ChatId, attachmentId: UUID) = "attachments/$chatId/$attachmentId"

    fun put(
        chatId: ChatId,
        attachmentId: UUID,
        sizeBytes: Long,
    ) {
        objects[key(chatId, attachmentId)] = sizeBytes
    }

    fun has(chatId: ChatId, attachmentId: UUID) = objects.containsKey(key(chatId, attachmentId))

    override suspend fun prepareUpload(
        chatId: ChatId,
        attachmentId: UUID,
        expiresIn: Duration,
    ): Resource<String> {
        signedUploads += key(chatId, attachmentId) to expiresIn
        return Resource.success("https://storage.test/upload/${key(chatId, attachmentId)}")
    }

    override suspend fun sizeOf(chatId: ChatId, attachmentId: UUID): Long? = objects[key(chatId, attachmentId)]

    override suspend fun prepareDownload(
        chatId: ChatId,
        attachmentId: UUID,
        expiresIn: Duration,
    ): Resource<String> = Resource.success("https://storage.test/download/${key(chatId, attachmentId)}")

    override suspend fun delete(chatId: ChatId, attachmentId: UUID): Boolean {
        if (failing) return false
        objects.remove(key(chatId, attachmentId))
        return true
    }
}
