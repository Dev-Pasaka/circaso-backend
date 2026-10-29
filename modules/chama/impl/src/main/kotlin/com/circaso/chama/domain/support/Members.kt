package com.circaso.chama.domain.support

import com.circaso.chama.domain.errors.ChamaError
import com.circaso.messaging.api.ChatDirectory
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.api.Profiles

/** Who may do what in a chama: members read and contribute, admins set the goal and approve, the treasurer pays. */
class Members(private val chats: ChatDirectory, private val profiles: Profiles) {
    suspend fun members(chatId: ChatId): Set<UserId> = chats.members(chatId)

    suspend fun admins(chatId: ChatId): Set<UserId> = chats.admins(chatId)

    suspend fun requireMember(chatId: ChatId, userId: UserId): Resource<Set<UserId>> {
        val members = members(chatId)
        return if (userId in members) Resource.success(members) else Resource.error(ChamaError.NotAMember)
    }

    suspend fun requireAdmin(chatId: ChatId, userId: UserId): Resource<Set<UserId>> {
        val members = requireMember(chatId, userId)
        if (members is Resource.Error) return members
        val admins = admins(chatId)
        return if (userId in admins) Resource.success(admins) else Resource.error(ChamaError.NotAdmin)
    }

    suspend fun name(userId: UserId): String = profiles.get(setOf(userId))[userId]?.displayName ?: SOMEONE

    private companion object {
        const val SOMEONE = "Someone"
    }
}
