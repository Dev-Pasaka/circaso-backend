package com.circaso.messaging.domain.usecases

import com.circaso.messaging.api.GroupLinkPage
import com.circaso.messaging.api.GroupLinkToken
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.repositories.GroupLinkRepository
import com.circaso.shared.Resource

/**
 * What the public page of a join link shows (`GroupLinkPages.page`, for share): the published preview and the member
 * count. Null for a link that was reset, turned off or never was: the page words them alike. Share counts the views
 * per caller address before asking.
 */
class ReadGroupLinkPage(private val links: GroupLinkRepository, private val chats: ChatRepository) {
    suspend operator fun invoke(token: GroupLinkToken): Resource<GroupLinkPage?> {
        val link = links.find(token)
        val preview = link?.preview
        if (link == null || preview == null || !link.isLive) return Resource.success(null)
        val chat = chats.chat(link.chatId) ?: return Resource.success(null)
        return Resource.success(
            GroupLinkPage(
                name = preview.name,
                description = preview.description,
                hasPhoto = preview.photo != null,
                photoVersion = link.updatedAt.toEpochMilli(),
                memberCount = chat.memberCount,
            ),
        )
    }
}
