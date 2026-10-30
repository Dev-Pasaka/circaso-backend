package com.circaso.messaging.domain.usecases

import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.AttachmentDownload
import com.circaso.messaging.domain.models.AttachmentState
import com.circaso.messaging.domain.ports.AttachmentStorage
import com.circaso.messaging.domain.repositories.AttachmentRepository
import com.circaso.messaging.domain.support.Membership
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Clock
import java.util.UUID

/**
 * AT3 `GET /attachments/{attachmentId}`: a 15-minute download link for anyone currently in the attachment's chat. A
 * pending or expired attachment, and a caller outside the chat, all get `attachment_not_found`, so nobody learns
 * that it exists.
 */
class GetAttachmentDownload(
    private val attachments: AttachmentRepository,
    private val membership: Membership,
    private val storage: AttachmentStorage,
    private val config: MessagingConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(caller: UserId, attachmentId: UUID): Resource<AttachmentDownload> {
        val now = clock.instant()
        val attachment = attachments.find(attachmentId)
        val visible =
            attachment != null &&
                attachment.state == AttachmentState.READY &&
                attachment.expiresAt.isAfter(now) &&
                membership.isMember(attachment.chatId, caller)
        if (!visible) return Resource.error(MessagingError.AttachmentNotFound)

        val url =
            when (val signed = storage.prepareDownload(attachment.chatId, attachmentId, config.attachmentUrlTtl)) {
                is Resource.Success -> signed.data
                is Resource.Error -> return signed
            }
        return Resource.success(AttachmentDownload(url, now.plus(config.attachmentUrlTtl), attachment.sizeBytes))
    }
}
