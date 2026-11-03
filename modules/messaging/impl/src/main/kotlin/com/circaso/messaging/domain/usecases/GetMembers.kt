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

/** `ChatDirectory.groupsOf`, for chama: the groups the user is in, read through `members_user_idx`. */
class ListGroupIds(private val chats: ChatRepository) {
    suspend operator fun invoke(userId: UserId): Resource<List<ChatId>> =
        Resource.success(chats.chatList(userId).filter { it.chat.kind == ChatKind.GROUP }.map { it.chat.chatId })
}

/** `ChatDirectory.owner`, for wallet and chama: the group's owner as the chat names it ([Chat.ownerId]). */
class GetOwner(private val chats: ChatRepository) {
    suspend operator fun invoke(chatId: ChatId): Resource<UserId?> =
        Resource.success(chats.chat(chatId)?.takeIf { it.kind == ChatKind.GROUP }?.ownerId)
}
