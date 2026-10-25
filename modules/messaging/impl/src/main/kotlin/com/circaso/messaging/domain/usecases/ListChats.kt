package com.circaso.messaging.domain.usecases

import com.circaso.messaging.domain.models.ChatView
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.support.ChatViews
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/**
 * M6 `GET /chats`: every chat the user is in, with members' public profiles and the last message id. The app sorts
 * and counts unread itself. A chat-list line whose chat is gone (a group deleted a moment ago) is skipped.
 */
class ListChats(private val chats: ChatRepository, private val views: ChatViews) {
    suspend operator fun invoke(userId: UserId): Resource<List<ChatView>> {
        val lines = chats.chatsOf(userId)
        val found = lines.mapNotNull { line -> chats.chat(line.chatId)?.let { it to line.lastMessageId } }
        return Resource.success(views.views(found))
    }
}
