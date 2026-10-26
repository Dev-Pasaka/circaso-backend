package com.circaso.messaging.domain.usecases

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.Chat
import com.circaso.messaging.domain.models.ChatKind
import com.circaso.messaging.domain.models.ChatView
import com.circaso.messaging.domain.models.Member
import com.circaso.messaging.domain.models.Role
import com.circaso.messaging.domain.models.RoleChange
import com.circaso.messaging.domain.repositories.ChatChanges
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.repositories.MessagingStore
import com.circaso.messaging.domain.support.ChatNotifier
import com.circaso.messaging.domain.support.ChatViews
import com.circaso.messaging.domain.support.ChatWriter
import com.circaso.messaging.domain.support.Membership
import com.circaso.shared.DomainError
import com.circaso.shared.ErrorKind
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/**
 * M11 `PUT /chats/{chatId}/members/{userId}/role`: an admin makes someone an admin or a member; only the owner can
 * make someone the owner, and then becomes an admin. The owner's own role changes only by handing over, so a group
 * always has exactly one owner.
 *
 * The decision is made inside the version check (decision 2026-10-26): every attempt re-reads both member rows and
 * the chat's owner, so of two handovers at once only one wins and the other is refused. The new owner is the chat's
 * `ownerId`, written by that check; the member rows follow. Members get `chat.updated`.
 */
class SetRole(
    private val chats: ChatRepository,
    private val writer: ChatWriter,
    private val store: MessagingStore,
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
        var rows = emptyList<Member>()
        val written =
            writer.update(chatId) { latest ->
                val decided =
                    decide(
                        latest,
                        caller,
                        target,
                        role,
                    )
                when (decided) {
                    is Resource.Error -> {
                        decided
                    }

                    is Resource.Success -> {
                        rows = decided.data.rows
                        Resource.success(decided.data.chat)
                    }
                }
            }
        val chat =
            when (written) {
                is Resource.Success -> {
                    written.data
                }

                is Resource.Error -> {
                    if (written.error != Unchanged) return written
                    chats.chat(chatId) ?: return Resource.error(MessagingError.ChatNotFound)
                }
            }
        if (written is Resource.Success) {
            store.commit(ChatChanges(roles = rows))
            notifier.chatUpdated(membership.members(chatId), chat)
        }
        return Resource.success(views.view(chat, chats.userChat(caller, chatId)?.lastMessageId))
    }

    /** Stops the version check when the role is already what was asked; answered as a success. */
    private data object Unchanged : DomainError {
        override val code = "messaging.unchanged"
        override val message = "Nothing to change."
        override val kind = ErrorKind.CONFLICT
    }

    private suspend fun decide(
        latest: Chat,
        caller: UserId,
        target: UserId,
        role: Role,
    ): Resource<RoleChange> {
        if (latest.kind != ChatKind.GROUP) return Resource.error(MessagingError.NotAdmin)
        val actor = chats.member(latest.chatId, caller) ?: return Resource.error(MessagingError.ChatNotFound)
        val subject = chats.member(latest.chatId, target) ?: return Resource.error(MessagingError.ChatNotFound)
        val actorRole = latest.roleOf(actor)
        val subjectRole = latest.roleOf(subject)
        if (!actorRole.isAdmin) return Resource.error(MessagingError.NotAdmin)
        if (role == Role.OWNER) {
            return handOver(
                latest,
                actor,
                subject,
                actorRole,
            )
        }
        if (subjectRole == Role.OWNER) return Resource.error(MessagingError.NotAdmin)
        if (subjectRole == role) return Resource.error(Unchanged)
        return Resource.success(RoleChange(latest, listOf(subject.copy(role = role))))
    }

    private fun handOver(
        latest: Chat,
        actor: Member,
        subject: Member,
        actorRole: Role,
    ): Resource<RoleChange> {
        if (actorRole != Role.OWNER) return Resource.error(MessagingError.NotAdmin)
        if (subject.userId == actor.userId) return Resource.error(Unchanged)
        val rows = listOf(subject.copy(role = Role.OWNER), actor.copy(role = Role.ADMIN))
        return Resource.success(RoleChange(latest.copy(ownerId = subject.userId), rows))
    }
}
