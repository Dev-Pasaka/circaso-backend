package com.circaso.messaging.domain.support

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.ports.MembersCache
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.shared.UserId

/**
 * Who is in a chat, read through `msg:members:{chatId}` (10 minutes) so sends and fan-out don't read
 * `members_by_chat` each time. A change marks the entry for a few seconds rather than deleting it, and readers fill
 * it only if absent, so a reader that read the rows just before a change can't put the old list back.
 */
class Membership(private val chats: ChatRepository, private val cache: MembersCache) {
    suspend fun members(chatId: ChatId): Set<UserId> {
        cache.get(chatId)?.let { return it }
        val members =
            chats
                .members(chatId)
                .map { it.userId }
                .toSet()
        if (members.isNotEmpty()) cache.fillIfAbsent(chatId, members)
        return members
    }

    suspend fun isMember(chatId: ChatId, userId: UserId): Boolean = userId in members(chatId)

    suspend fun changed(chatId: ChatId) = cache.markChanged(chatId)
}
