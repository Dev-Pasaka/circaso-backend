package com.circaso.messaging.presentation.api

import com.circaso.messaging.api.ChatDirectory
import com.circaso.messaging.api.ChatId
import com.circaso.shared.UserId

/**
 * Placeholder until chats exist: nobody is a member of anything, so a caller that checks membership refuses
 * rather than lets someone act in a chat that was never checked. Nothing calls it yet (wallet and calls come later).
 */
class ChatDirectoryImpl : ChatDirectory {
    // TODO(MS3): answer from `members_by_chat` through the `msg:members:{chatId}` cache, via a service.
    override suspend fun isMember(chatId: ChatId, userId: UserId): Boolean = false

    // TODO(MS3): answer from `members_by_chat` through the `msg:members:{chatId}` cache, via a service.
    override suspend fun members(chatId: ChatId): Set<UserId> = emptySet()
}
