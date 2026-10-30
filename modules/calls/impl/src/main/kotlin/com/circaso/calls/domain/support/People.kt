package com.circaso.calls.domain.support

import com.circaso.calls.domain.errors.CallsError
import com.circaso.messaging.api.ChatDirectory
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.api.Profiles

/** Who may call in a chat (its members) and what the tiles call them. */
class People(private val chats: ChatDirectory, private val profiles: Profiles) {
    /** The chat's members when [userId] is one of them. */
    suspend fun requireMember(chatId: ChatId, userId: UserId): Resource<Set<UserId>> {
        val members = chats.members(chatId)
        return if (userId in members) Resource.success(members) else Resource.error(CallsError.NotAMember)
    }

    suspend fun isGroup(chatId: ChatId): Boolean = chats.isGroup(chatId)

    suspend fun names(userIds: Set<UserId>): Map<UserId, String> {
        val profiles = profiles.get(userIds)
        return userIds.associateWith { profiles[it]?.displayName ?: SOMEONE }
    }

    suspend fun name(userId: UserId): String = names(setOf(userId)).getValue(userId)

    private companion object {
        const val SOMEONE = "Someone"
    }
}
