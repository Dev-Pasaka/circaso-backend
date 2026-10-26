package com.circaso.messaging.domain.support

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.models.ChatKind
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.shared.UserId

/**
 * Whether two people are in a group together: inside a group, members start sessions with each other without a
 * contact connection (Messaging → Depends on). Two chat-list reads, intersected, whatever the number of groups.
 */
class SharedGroups(private val chats: ChatRepository) {
    suspend fun share(a: UserId, b: UserId): Boolean {
        val mine = groupsOf(a)
        if (mine.isEmpty()) return false
        return groupsOf(b).any { it in mine }
    }

    private suspend fun groupsOf(userId: UserId): Set<ChatId> =
        chats
            .chatsOf(userId)
            .filter { it.kind == ChatKind.GROUP }
            .map { it.chatId }
            .toSet()
}
