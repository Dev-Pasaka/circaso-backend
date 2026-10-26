package com.circaso.messaging.domain.usecases

import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.Chat
import com.circaso.messaging.domain.models.ChatKind
import com.circaso.messaging.domain.models.ChatView
import com.circaso.messaging.domain.models.UpdateGroupCommand
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.support.ChatNotifier
import com.circaso.messaging.domain.support.ChatViews
import com.circaso.messaging.domain.support.Membership
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.messaging.domain.support.isValidSealedMeta
import com.circaso.shared.Resource

/**
 * M8 `PATCH /chats/{chatId}`: the sealed name and avatar, and whether members may add people; admins only. The
 * phone sends the `version` it last read, and the write is refused with `messaging.version_conflict` if the chat
 * changed since (Kandra's `IF version = ?`). A new seal raises `metaVersion`, so phones know to decrypt it again.
 */
class UpdateGroup(
    private val chats: ChatRepository,
    private val membership: Membership,
    private val views: ChatViews,
    private val notifier: ChatNotifier,
    private val config: MessagingConfig,
) {
    suspend operator fun invoke(command: UpdateGroupCommand): Resource<ChatView> {
        val caller =
            chats.member(command.chatId, command.caller) ?: return Resource.error(MessagingError.ChatNotFound)
        val current = chats.chat(command.chatId) ?: return Resource.error(MessagingError.ChatNotFound)
        if (current.kind != ChatKind.GROUP || !current.roleOf(caller).isAdmin) {
            return Resource.error(MessagingError.NotAdmin)
        }
        val sealedMeta = command.sealedMeta
        if (sealedMeta != null && !isValidSealedMeta(sealedMeta, config.maxSealedMetaBytes)) {
            return Resource.error(MessagingError.GroupInvalid)
        }
        if (current.version != command.version) return Resource.error(MessagingError.VersionConflict)

        val updated =
            chats.updateChat(current, current.changedBy(command))
                ?: return Resource.error(MessagingError.VersionConflict)
        notifier.chatUpdated(membership.members(updated.chatId), updated)
        val last = chats.userChat(command.caller, updated.chatId)?.lastMessageId
        return Resource.success(views.view(updated, last))
    }

    private fun Chat.changedBy(command: UpdateGroupCommand): Chat {
        val resealed = command.sealedMeta != null && command.sealedMeta != sealedMeta
        return copy(
            sealedMeta = command.sealedMeta ?: sealedMeta,
            metaVersion = if (resealed) metaVersion + 1 else metaVersion,
            settings = command.membersCanAdd?.let { settings.copy(membersCanAdd = it) } ?: settings,
        )
    }
}
