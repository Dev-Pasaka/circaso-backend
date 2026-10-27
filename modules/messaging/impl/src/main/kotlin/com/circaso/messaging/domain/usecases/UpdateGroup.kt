package com.circaso.messaging.domain.usecases

import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.Chat
import com.circaso.messaging.domain.models.ChatKind
import com.circaso.messaging.domain.models.ChatView
import com.circaso.messaging.domain.models.UpdateGroupCommand
import com.circaso.messaging.domain.ports.Transactor
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
 * changed since (`UPDATE … WHERE version = ?`). The caller's right is read with the chat's row locked, so a demotion
 * a moment earlier can't slip past it. A new seal raises `metaVersion`, so phones know to decrypt it again.
 */
class UpdateGroup(
    private val chats: ChatRepository,
    private val transactor: Transactor,
    private val membership: Membership,
    private val views: ChatViews,
    private val notifier: ChatNotifier,
    private val config: MessagingConfig,
) {
    suspend operator fun invoke(command: UpdateGroupCommand): Resource<ChatView> {
        val sealedMeta = command.sealedMeta
        val written =
            transactor.attempt("chats.update-group") {
                val current =
                    chats.lockChat(command.chatId) ?: return@attempt Resource.error(MessagingError.ChatNotFound)
                val caller =
                    chats.member(command.chatId, command.caller)
                        ?: return@attempt Resource.error(MessagingError.ChatNotFound)
                if (current.kind != ChatKind.GROUP || !current.roleOf(caller).isAdmin) {
                    return@attempt Resource.error(MessagingError.NotAdmin)
                }
                if (sealedMeta != null && !isValidSealedMeta(sealedMeta, config.maxSealedMetaBytes)) {
                    return@attempt Resource.error(MessagingError.GroupInvalid)
                }
                if (current.version != command.version) return@attempt Resource.error(MessagingError.VersionConflict)
                chats.updateChat(current, current.changedBy(command))?.let { Resource.success(it) }
                    ?: Resource.error(MessagingError.VersionConflict)
            }
        val updated =
            when (written) {
                is Resource.Error -> return written
                is Resource.Success -> written.data
            }
        notifier.chatUpdated(membership.members(updated.chatId), updated)
        return Resource.success(views.view(updated, chats.lastMessageId(updated.chatId)))
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
