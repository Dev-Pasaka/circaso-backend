package com.circaso.messaging.domain.usecases

import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.AttachmentKind
import com.circaso.messaging.domain.models.AttachmentState
import com.circaso.messaging.domain.models.AvatarChange
import com.circaso.messaging.domain.models.Chat
import com.circaso.messaging.domain.models.ChatKind
import com.circaso.messaging.domain.models.ChatView
import com.circaso.messaging.domain.models.UpdateGroupCommand
import com.circaso.messaging.domain.ports.Transactor
import com.circaso.messaging.domain.repositories.AttachmentRepository
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.support.ChatNotifier
import com.circaso.messaging.domain.support.ChatViews
import com.circaso.messaging.domain.support.Membership
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.messaging.domain.support.isValidSealedMeta
import com.circaso.shared.Resource
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * M8 `PATCH /chats/{chatId}`: the sealed name and avatar, the group photo, and whether members may add people; admins
 * only. The phone sends the `version` it last read, and the write is refused with `messaging.version_conflict` if the
 * chat changed since (`UPDATE … WHERE version = ?`). The caller's right is read with the chat's row locked, so a
 * demotion a moment earlier can't slip past it. A new seal raises `metaVersion`, so phones know to decrypt it again.
 *
 * The group photo (decision 2026-11-05) is an image attachment of this group, already completed (AT2). Making it the
 * photo clears its expiry in the same transaction, so the purge never takes it; the photo it replaces, or a cleared
 * one, gets an expiry [MessagingConfig.groupPhotoGrace] away, so a member fetching it right now still can.
 */
class UpdateGroup(
    private val chats: ChatRepository,
    private val attachments: AttachmentRepository,
    private val transactor: Transactor,
    private val membership: Membership,
    private val views: ChatViews,
    private val notifier: ChatNotifier,
    private val config: MessagingConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: UpdateGroupCommand): Resource<ChatView> {
        val written = transactor.attempt("chats.update-group") { write(command, clock.instant()) }
        val updated =
            when (written) {
                is Resource.Error -> return written
                is Resource.Success -> written.data
            }
        notifier.chatUpdated(membership.members(updated.chatId), updated)
        return Resource.success(views.view(updated, chats.lastMessageId(updated.chatId)))
    }

    /** Inside the transaction, with the chat's row locked before the caller's role or any attachment is read. */
    private suspend fun write(command: UpdateGroupCommand, now: Instant): Resource<Chat> {
        val sealedMeta = command.sealedMeta
        val current = chats.lockChat(command.chatId) ?: return Resource.error(MessagingError.ChatNotFound)
        val caller = chats.member(command.chatId, command.caller) ?: return Resource.error(MessagingError.ChatNotFound)
        if (current.kind != ChatKind.GROUP || !current.roleOf(caller).isAdmin) {
            return Resource.error(MessagingError.NotAdmin)
        }
        if (sealedMeta != null && !isValidSealedMeta(sealedMeta, config.maxSealedMetaBytes)) {
            return Resource.error(MessagingError.GroupInvalid)
        }
        if (current.version != command.version) return Resource.error(MessagingError.VersionConflict)

        val avatarMediaId =
            when (val avatar = changeAvatar(current, command.avatar, now)) {
                is Resource.Error -> return avatar
                is Resource.Success -> avatar.data
            }
        return chats.updateChat(current, current.changedBy(command, avatarMediaId))?.let { Resource.success(it) }
            ?: Resource.error(MessagingError.VersionConflict)
    }

    /** The photo the chat will have: kept from the purge if new, and the one it replaces released. */
    private suspend fun changeAvatar(
        chat: Chat,
        change: AvatarChange?,
        now: Instant,
    ): Resource<UUID?> {
        val next =
            when (change) {
                null -> return Resource.success(chat.avatarMediaId)
                AvatarChange.Remove -> null
                is AvatarChange.Set -> change.attachmentId
            }
        if (next != null && next != chat.avatarMediaId && !keep(chat, next, now)) {
            return Resource.error(MessagingError.AvatarInvalid)
        }
        val previous = chat.avatarMediaId
        if (previous != null && previous != next) {
            attachments.releaseGroupPhoto(previous, now.plus(config.groupPhotoGrace))
        }
        return Resource.success(next)
    }

    /** Whether [attachmentId] is a completed image of [chat] within the size limit, now kept with no expiry. */
    private suspend fun keep(
        chat: Chat,
        attachmentId: UUID,
        now: Instant,
    ): Boolean {
        val attachment = attachments.find(attachmentId) ?: return false
        val isPhoto =
            attachment.chatId == chat.chatId &&
                attachment.state == AttachmentState.READY &&
                attachment.kind == AttachmentKind.IMAGE &&
                attachment.sizeBytes <= config.maxGroupPhotoBytes &&
                attachment.isLiveAt(now)
        return isPhoto && attachments.keepAsGroupPhoto(attachmentId, now)
    }

    private fun Chat.changedBy(command: UpdateGroupCommand, avatarMediaId: UUID?): Chat {
        val resealed = command.sealedMeta != null && command.sealedMeta != sealedMeta
        return copy(
            sealedMeta = command.sealedMeta ?: sealedMeta,
            metaVersion = if (resealed) metaVersion + 1 else metaVersion,
            settings = command.membersCanAdd?.let { settings.copy(membersCanAdd = it) } ?: settings,
            avatarMediaId = avatarMediaId,
        )
    }
}
