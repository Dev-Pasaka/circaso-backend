package com.circaso.messaging.domain.usecases

import com.circaso.messaging.api.GroupLinkToken
import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.LinkLookup
import com.circaso.messaging.domain.ports.LinkRateLimit
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.repositories.GroupLinkRepository
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/**
 * M31 `GET /chats/links/{token}` (decision 2026-11-14): what a join link shows a signed-in person before they join:
 * the published name, description and photo, and how many people are in the group. Never its members or messages.
 * Someone already in the group also gets its id, so their app opens the chat. Sixty lookups per person per hour, so
 * tokens can't be walked; a link that was reset or turned off looks the same as one that never was.
 */
class LookUpGroupLink(
    private val links: GroupLinkRepository,
    private val chats: ChatRepository,
    private val rateLimit: LinkRateLimit,
    private val config: MessagingConfig,
) {
    suspend operator fun invoke(caller: UserId, token: GroupLinkToken): Resource<LinkLookup> {
        rateLimit.take(ACTION, caller, config.linkLookupsPerHour)?.let {
            return Resource.error(MessagingError.RateLimited(it))
        }
        val link = links.find(token)?.takeIf { it.isLive } ?: return Resource.error(MessagingError.LinkNotFound)
        val chat = chats.chat(link.chatId) ?: return Resource.error(MessagingError.LinkNotFound)
        val isMember = chats.member(link.chatId, caller) != null
        return Resource.success(LinkLookup(link, chat.memberCount, link.chatId.takeIf { isMember }))
    }

    private companion object {
        const val ACTION = "lookup"
    }
}
