package com.circaso.messaging.domain.usecases

import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.ATTACHMENT_CONTENT_TYPE
import com.circaso.messaging.domain.models.Attachment
import com.circaso.messaging.domain.models.AttachmentState
import com.circaso.messaging.domain.models.AttachmentUpload
import com.circaso.messaging.domain.models.PrepareAttachmentCommand
import com.circaso.messaging.domain.ports.AttachmentStorage
import com.circaso.messaging.domain.ports.Transactor
import com.circaso.messaging.domain.repositories.AttachmentRepository
import com.circaso.messaging.domain.support.Membership
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.shared.Resource
import com.circaso.shared.Uuid7
import java.time.Clock

/**
 * AT1 `POST /chats/{chatId}/attachments`: a pre-signed PUT for one encrypted file, and its `PENDING` row. The URL is
 * signed before the row is written, so a storage outage leaves nothing behind; a row whose upload never completes is
 * purged after a day.
 */
class PrepareAttachmentUpload(
    private val attachments: AttachmentRepository,
    private val transactor: Transactor,
    private val membership: Membership,
    private val storage: AttachmentStorage,
    private val config: MessagingConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: PrepareAttachmentCommand): Resource<AttachmentUpload> {
        if (command.sizeBytes !in 1..config.maxAttachmentBytes) {
            return Resource.error(MessagingError.AttachmentTooLarge)
        }
        if (!membership.isMember(command.chatId, command.caller)) return Resource.error(MessagingError.ChatNotFound)

        val now = clock.instant()
        val attachmentId = Uuid7.generate()
        val url =
            when (val signed = storage.prepareUpload(command.chatId, attachmentId, config.attachmentUploadUrlTtl)) {
                is Resource.Success -> signed.data
                is Resource.Error -> return signed
            }
        val attachment =
            Attachment(
                attachmentId = attachmentId,
                chatId = command.chatId,
                uploaderId = command.caller,
                kind = command.kind,
                sizeBytes = command.sizeBytes,
                state = AttachmentState.PENDING,
                createdAt = now,
                expiresAt = now.plus(config.attachmentRetention),
            )
        transactor.run("attachments.prepare") { attachments.insert(attachment) }

        return Resource.success(
            AttachmentUpload(
                attachmentId = attachmentId,
                uploadUrl = url,
                contentType = ATTACHMENT_CONTENT_TYPE,
                expiresAt = now.plus(config.attachmentUploadUrlTtl),
            ),
        )
    }
}
