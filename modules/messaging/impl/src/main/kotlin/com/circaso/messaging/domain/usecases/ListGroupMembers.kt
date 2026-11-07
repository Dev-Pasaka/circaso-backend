package com.circaso.messaging.domain.usecases

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.models.ChatKind
import com.circaso.messaging.domain.models.GroupMemberView
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.shared.Resource

/**
 * Staff: a group's members, the owner first and then by when they joined (decision 2026-11-08), each with the role
 * the chat gives them: the owner is whoever the chat names, so a member row still saying OWNER after a handover shows
 * as an admin. A group has at most 1,000 members, so all are read and the first [limit] kept; a direct chat or a chat
 * that is gone has none.
 */
class ListGroupMembers(private val chats: ChatRepository) {
    suspend operator fun invoke(chatId: ChatId, limit: Int): Resource<List<GroupMemberView>> {
        val chat = chats.chat(chatId)?.takeIf { it.kind == ChatKind.GROUP } ?: return Resource.success(emptyList())
        val ordered =
            chats
                .members(chatId)
                .sortedWith(compareBy({ it.userId != chat.ownerId }, { it.joinedAt }, { it.userId.value }))
        return Resource.success(
            ordered.take(limit.coerceIn(1, MAX_MEMBERS)).map { GroupMemberView(it, chat.roleOf(it)) },
        )
    }

    private companion object {
        const val MAX_MEMBERS = 1_000
    }
}
