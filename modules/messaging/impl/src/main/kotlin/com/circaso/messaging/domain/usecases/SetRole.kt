package com.circaso.messaging.domain.usecases

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.ChatKind
import com.circaso.messaging.domain.models.ChatView
import com.circaso.messaging.domain.models.Member
import com.circaso.messaging.domain.models.Role
import com.circaso.messaging.domain.repositories.ChatChanges
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.repositories.MessagingStore
import com.circaso.messaging.domain.support.ChatNotifier
import com.circaso.messaging.domain.support.ChatViews
import com.circaso.messaging.domain.support.ChatWriter
import com.circaso.messaging.domain.support.Membership
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/**
 * M11 `PUT /chats/{chatId}/members/{userId}/role`: an admin makes someone an admin or a member; only the owner can
 * make someone the owner, and then becomes an admin. The owner's own role changes only by handing over, so a group
 * always has exactly one owner. Both member rows of a handover go in one batch. A change moves the chat's version on
 * and members get `chat.updated`, so their phones reload the roles (decision 2026-10-26).
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
        val actor = chats.member(chatId, caller) ?: return Resource.error(MessagingError.ChatNotFound)
        val chat = chats.chat(chatId) ?: return Resource.error(MessagingError.ChatNotFound)
        if (chat.kind != ChatKind.GROUP || !actor.role.isAdmin) return Resource.error(MessagingError.NotAdmin)
        val subject = chats.member(chatId, target) ?: return Resource.error(MessagingError.ChatNotFound)

        val changed = changes(actor, subject, role) ?: return Resource.error(MessagingError.NotAdmin)
        val last = chats.userChat(caller, chatId)?.lastMessageId
        if (changed.isEmpty()) return Resource.success(views.view(chat, last))

        val bumped =
            when (val written = writer.update(chatId) { Resource.success(it) }) {
                is Resource.Error -> return written
                is Resource.Success -> written.data
            }
        store.commit(ChatChanges(roles = changed))
        notifier.chatUpdated(membership.members(chatId), bumped)
        return Resource.success(views.view(bumped, last))
    }

    /** The member rows to rewrite, empty when nothing changes, or null when [actor] may not do it. */
    private fun changes(
        actor: Member,
        subject: Member,
        role: Role,
    ): List<Member>? {
        if (role == Role.OWNER) {
            if (actor.role != Role.OWNER) return null
            if (subject.userId == actor.userId) return emptyList()
            return listOf(subject.copy(role = Role.OWNER), actor.copy(role = Role.ADMIN))
        }
        if (subject.role == Role.OWNER) return null
        return if (subject.role == role) emptyList() else listOf(subject.copy(role = role))
    }
}
