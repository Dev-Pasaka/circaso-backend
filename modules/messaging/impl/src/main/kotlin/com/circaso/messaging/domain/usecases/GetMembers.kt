package com.circaso.messaging.domain.usecases

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.models.ChatKind
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.support.Membership
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** `ChatDirectory`, for wallet and calls: who is in a chat, through the `msg:members` cache. Empty for no chat. */
class GetMembers(private val membership: Membership) {
    suspend operator fun invoke(chatId: ChatId): Resource<Set<UserId>> = Resource.success(membership.members(chatId))
}

/** `ChatDirectory.isGroup`, for calls: a declined direct call ends it, a declined group call doesn't. */
class IsGroup(private val chats: ChatRepository) {
    suspend operator fun invoke(chatId: ChatId): Resource<Boolean> =
        Resource.success(chats.chat(chatId)?.kind == ChatKind.GROUP)
}

/** `ChatDirectory.admins`, for chama: who may approve a group's payments. Empty for no chat. */
class GetAdmins(private val membership: Membership) {
    suspend operator fun invoke(chatId: ChatId): Resource<Set<UserId>> = Resource.success(membership.admins(chatId))
}
