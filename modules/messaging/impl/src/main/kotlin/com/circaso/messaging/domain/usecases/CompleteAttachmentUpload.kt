package com.circaso.messaging.domain.usecases

import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.Attachment
import com.circaso.messaging.domain.models.AttachmentState
import com.circaso.messaging.domain.ports.AttachmentStorage
import com.circaso.messaging.domain.ports.Transactor
import com.circaso.messaging.domain.repositories.AttachmentRepository
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Clock
import java.util.UUID

/**
 * AT2 `POST /attachments/{attachmentId}/complete`: the uploader says the PUT is done. The object must be in storage at
 * exactly the size AT1 promised, then the row is `READY`. Completing a ready attachment again answers the same, so a
 * retried request is harmless.
 */
class CompleteAttachmentUpload(
    private val attachments: AttachmentRepository,
    private val transactor: Transactor,
    private val storage: AttachmentStorage,
    private val clock: Clock,
) {
    suspend operator fun invoke(caller: UserId, attachmentId: UUID): Resource<Attachment> {
        val attachment = attachments.find(attachmentId)
        if (attachment == null || attachment.uploaderId != caller || !attachment.expiresAt.isAfter(clock.instant())) {
            return Resource.error(MessagingError.AttachmentNotFound)
        }
        if (attachment.state == AttachmentState.READY) return Resource.success(attachment)

        val stored = storage.sizeOf(attachment.chatId, attachmentId)
        if (stored != attachment.sizeBytes) return Resource.error(MessagingError.AttachmentIncomplete)

        val ready = transactor.run("attachments.complete") { attachments.markReady(attachmentId) }
        if (!ready && attachments.find(attachmentId)?.state != AttachmentState.READY) {
            return Resource.error(MessagingError.AttachmentNotFound)
        }
        return Resource.success(attachment.copy(state = AttachmentState.READY))
    }
}
