package com.circaso.messaging.domain.usecases

import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.Attachment
import com.circaso.messaging.domain.models.AttachmentDownload
import com.circaso.messaging.domain.models.AttachmentState
import com.circaso.messaging.domain.ports.AttachmentStorage
import com.circaso.messaging.domain.repositories.AttachmentRepository
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.repositories.InviteRepository
import com.circaso.messaging.domain.support.Membership
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * AT3 `GET /attachments/{attachmentId}`: a 15-minute download link for anyone currently in the attachment's chat, and
 * for someone with an open invite to a group, its current photo only (decision 2026-11-09: the invite shows the
 * group's name and photo, never anything else of it). A pending or expired attachment, and any other caller, all get
 * `attachment_not_found`, so nobody learns that it exists.
 */
class GetAttachmentDownload(
    private val attachments: AttachmentRepository,
    private val membership: Membership,
    private val chats: ChatRepository,
    private val invites: InviteRepository,
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
                attachment.isLiveAt(now) &&
                (membership.isMember(attachment.chatId, caller) || isInvitedToItsPhoto(attachment, caller, now))
        if (!visible) return Resource.error(MessagingError.AttachmentNotFound)

        val url =
            when (val signed = storage.prepareDownload(attachment.chatId, attachmentId, config.attachmentUrlTtl)) {
                is Resource.Success -> signed.data
                is Resource.Error -> return signed
            }
        return Resource.success(AttachmentDownload(url, now.plus(config.attachmentUrlTtl), attachment.sizeBytes))
    }

    private suspend fun isInvitedToItsPhoto(
        attachment: Attachment,
        caller: UserId,
        now: Instant,
    ): Boolean {
        val chat = chats.chat(attachment.chatId) ?: return false
        if (chat.avatarMediaId != attachment.attachmentId) return false
        return invites.openFor(chat.chatId, listOf(caller), now).isNotEmpty()
    }
}
