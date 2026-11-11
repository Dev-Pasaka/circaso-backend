package com.circaso.messaging.presentation.api

import com.circaso.identity.api.GroupMembership
import com.circaso.messaging.api.ChatId
import com.circaso.messaging.presentation.services.ChatService
import com.circaso.shared.UserId
import com.circaso.shared.getOrNull
import java.util.UUID

/**
 * identity:api's `GroupMembership`, for its group key backup (2026-11-12): whether a person may back up a chat's meta
 * key, answered through the `msg:members` cache like [ChatDirectoryImpl]. A chat that doesn't exist has no members.
 */
class GroupMembershipImpl(private val chats: ChatService) : GroupMembership {
    override suspend fun isMember(chatId: UUID, userId: UserId): Boolean =
        userId in chats.members(ChatId(chatId)).getOrNull().orEmpty()
}
