package com.circaso.messaging.domain.usecases

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.Chat
import com.circaso.messaging.domain.models.ChatKind
import com.circaso.messaging.domain.models.ChatView
import com.circaso.messaging.domain.models.Member
import com.circaso.messaging.domain.models.Role
import com.circaso.messaging.domain.models.RoleChange
import com.circaso.messaging.domain.ports.Transactor
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.support.ChatNotifier
import com.circaso.messaging.domain.support.ChatViews
import com.circaso.messaging.domain.support.Membership
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/**
 * M11 `PUT /chats/{chatId}/members/{userId}/role`: an admin makes someone an admin or a member; only the owner can
 * make someone the owner, and then becomes an admin. The owner's own role changes only by handing over, so a group
 * always has exactly one owner.
 *
 * One transaction with the chat's row locked first: both member rows and the chat's owner are read under the lock,
 * so of two handovers at once the second sees the first's result and is refused. The new owner is the chat's
 * `ownerId` (the version moves on); the member rows follow with `UPDATE … RETURNING`, each only if it still exists,
 * so a role written as someone leaves never puts them back. Members get `chat.updated`.
 */
class SetRole(
    private val chats: ChatRepository,
    private val transactor: Transactor,
    private val membership: Membership,
    private val views: ChatViews,
    private val notifier: ChatNotifier,
) {
    suspend operator fun invoke(
        caller: UserId,
        chatId: ChatId,
        target: UserId,
        role: Role,
    ): Resource<ChatView> {
        val written =
            transactor.attempt("chats.set-role") {
                val latest = chats.lockChat(chatId) ?: return@attempt Resource.error(MessagingError.ChatNotFound)
                when (val decided = decide(latest, caller, target, role)) {
                    is Resource.Error -> {
                        decided
                    }

                    is Resource.Success -> {
                        write(latest, decided.data)
                    }
                }
            }
        val change =
            when (written) {
                is Resource.Error -> return written
                is Resource.Success -> written.data
            }
        if (change != null) notifier.chatUpdated(membership.members(chatId), change.chat)
        val chat = change?.chat ?: chats.chat(chatId) ?: return Resource.error(MessagingError.ChatNotFound)
        return Resource.success(views.view(chat, chats.lastMessageId(chatId)))
    }

    /** The chat (a new owner, a new version) and then the member rows; null when nothing needed changing. */
    private suspend fun write(latest: Chat, change: RoleChange?): Resource<RoleChange?> {
        if (change == null) return Resource.success(null)
        val updated = chats.updateChat(latest, change.chat) ?: return Resource.error(MessagingError.VersionConflict)
        for (row in change.rows) chats.setRole(row)
        return Resource.success(RoleChange(updated, change.rows))
    }

    /** Under the chat's lock: what to write, null when the role already is what was asked, or why not. */
    private suspend fun decide(
        latest: Chat,
        caller: UserId,
        target: UserId,
        role: Role,
    ): Resource<RoleChange?> {
        if (latest.kind != ChatKind.GROUP) return Resource.error(MessagingError.NotAdmin)
        val actor = chats.member(latest.chatId, caller) ?: return Resource.error(MessagingError.ChatNotFound)
        val subject = chats.member(latest.chatId, target) ?: return Resource.error(MessagingError.ChatNotFound)
        val actorRole = latest.roleOf(actor)
        val subjectRole = latest.roleOf(subject)
        if (!actorRole.isAdmin) return Resource.error(MessagingError.NotAdmin)
        if (role == Role.OWNER) return handOver(latest, actor, subject, actorRole)
        if (subjectRole == Role.OWNER) return Resource.error(MessagingError.NotAdmin)
        if (subjectRole == role) return Resource.success(null)
        return Resource.success(RoleChange(latest, listOf(subject.copy(role = role))))
    }

    private fun handOver(
        latest: Chat,
        actor: Member,
        subject: Member,
        actorRole: Role,
    ): Resource<RoleChange?> {
        if (actorRole != Role.OWNER) return Resource.error(MessagingError.NotAdmin)
        if (subject.userId == actor.userId) return Resource.success(null)
        val rows = listOf(subject.copy(role = Role.OWNER), actor.copy(role = Role.ADMIN))
        return Resource.success(RoleChange(latest.copy(ownerId = subject.userId), rows))
    }
}
