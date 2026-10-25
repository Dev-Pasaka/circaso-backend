package com.circaso.messaging.domain.usecases

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.ChatKind
import com.circaso.messaging.domain.models.Role
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.support.MemberRemoval
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/**
 * M10 `DELETE /chats/{chatId}/members/{userId}` by an admin, for someone else. Admins remove admins and members;
 * nobody removes the owner, whose way out is to hand over ownership and leave. The admin's phone then makes a new
 * group metadata key and re-seals the name (Messaging → Group metadata key).
 */
class RemoveMember(private val chats: ChatRepository, private val removal: MemberRemoval) {
    suspend operator fun invoke(
        caller: UserId,
        chatId: ChatId,
        target: UserId,
    ): Resource<Unit> {
        val admin = chats.member(chatId, caller) ?: return Resource.error(MessagingError.ChatNotFound)
        val chat = chats.chat(chatId) ?: return Resource.error(MessagingError.ChatNotFound)
        if (chat.kind != ChatKind.GROUP || !admin.role.isAdmin) return Resource.error(MessagingError.NotAdmin)
        val member = chats.member(chatId, target) ?: return Resource.error(MessagingError.ChatNotFound)
        // TODO(decision: may an admin remove another admin, and may anyone remove the owner? Built: yes and no.)
        if (member.role == Role.OWNER) return Resource.error(MessagingError.NotAdmin)
        return removal.remove(member, by = caller)
    }
}
