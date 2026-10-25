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
 * M10 `DELETE /chats/{chatId}/members/{userId}` for oneself: leaving a group. A direct chat can't be left. The last
 * member leaving deletes the group (`messaging.GroupDeleted`).
 */
class LeaveGroup(private val chats: ChatRepository, private val removal: MemberRemoval) {
    suspend operator fun invoke(caller: UserId, chatId: ChatId): Resource<Unit> {
        val me = chats.member(chatId, caller) ?: return Resource.error(MessagingError.ChatNotFound)
        val chat = chats.chat(chatId) ?: return Resource.error(MessagingError.ChatNotFound)
        if (chat.kind != ChatKind.GROUP) return Resource.error(MessagingError.NotAdmin)
        // TODO(decision: what happens to a group when its owner leaves while others remain; refused until then.)
        if (me.role == Role.OWNER && chats.members(chatId).size > 1) {
            return Resource.error(MessagingError.OwnerMustHandOver)
        }
        return removal.remove(me, by = caller)
    }
}
