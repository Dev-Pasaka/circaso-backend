package com.circaso.messaging.domain.usecases

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.support.Membership
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** `ChatDirectory`, for wallet and calls: who is in a chat, through the `msg:members` cache. Empty for no chat. */
class GetMembers(private val membership: Membership) {
    suspend operator fun invoke(chatId: ChatId): Resource<Set<UserId>> = Resource.success(membership.members(chatId))
}
