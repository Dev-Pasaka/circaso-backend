package com.circaso.messaging.domain.usecases

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.ChatKind
import com.circaso.messaging.domain.models.GroupLink
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.repositories.GroupLinkRepository
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/**
 * M28 `GET /chats/{chatId}/link` (decision 2026-11-14): the group's join link, for its admins; null when it has none
 * (never made, or turned off). A chat the caller isn't in answers as if it didn't exist.
 */
class GetGroupLink(private val chats: ChatRepository, private val links: GroupLinkRepository) {
    suspend operator fun invoke(caller: UserId, chatId: ChatId): Resource<GroupLink?> {
        val member = chats.member(chatId, caller) ?: return Resource.error(MessagingError.ChatNotFound)
        val chat = chats.chat(chatId) ?: return Resource.error(MessagingError.ChatNotFound)
        if (chat.kind != ChatKind.GROUP) return Resource.error(MessagingError.NotAGroup)
        if (!chat.roleOf(member).isAdmin) return Resource.error(MessagingError.NotAdmin)
        return Resource.success(links.liveOf(chatId))
    }
}
