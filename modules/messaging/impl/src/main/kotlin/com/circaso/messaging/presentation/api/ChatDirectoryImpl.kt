package com.circaso.messaging.presentation.api

import com.circaso.messaging.api.ChatDirectory
import com.circaso.messaging.api.ChatId
import com.circaso.messaging.presentation.services.ChatService
import com.circaso.shared.UserId
import com.circaso.shared.getOrNull

/**
 * messaging:api for wallet (pay-in-chat) and calls (group calls), answered through the `msg:members` cache. A chat
 * that doesn't exist has no members, so a caller that checks membership refuses.
 */
class ChatDirectoryImpl(private val chats: ChatService) : ChatDirectory {
    override suspend fun isMember(chatId: ChatId, userId: UserId): Boolean = userId in members(chatId)

    override suspend fun members(chatId: ChatId): Set<UserId> = chats.members(chatId).getOrNull().orEmpty()

    override suspend fun admins(chatId: ChatId): Set<UserId> = chats.admins(chatId).getOrNull().orEmpty()

    override suspend fun isGroup(chatId: ChatId): Boolean = chats.isGroup(chatId).getOrNull() ?: false

    override suspend fun groupsOf(userId: UserId): List<ChatId> = chats.groupsOf(userId).getOrNull().orEmpty()
}
