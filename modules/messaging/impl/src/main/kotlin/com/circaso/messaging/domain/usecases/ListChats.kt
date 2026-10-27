package com.circaso.messaging.domain.usecases

import com.circaso.messaging.domain.models.ChatView
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.support.ChatViews
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/**
 * M6 `GET /chats`: every chat the user is in, with members' public profiles and the newest message id the server
 * still holds (null after 30 days without a message). The app sorts and counts unread itself.
 */
class ListChats(private val chats: ChatRepository, private val views: ChatViews) {
    suspend operator fun invoke(userId: UserId): Resource<List<ChatView>> {
        val lines = chats.chatList(userId)
        return Resource.success(views.views(lines.map { it.chat to it.lastMessageId }))
    }
}
