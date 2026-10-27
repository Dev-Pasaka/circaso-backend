package com.circaso.messaging.domain.usecases

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.ChatView
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.support.ChatViews
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** M7 `GET /chats/{chatId}`: one chat with members and roles. Someone not in it gets the same answer as no chat. */
class GetChat(private val chats: ChatRepository, private val views: ChatViews) {
    suspend operator fun invoke(caller: UserId, chatId: ChatId): Resource<ChatView> {
        if (chats.member(chatId, caller) == null) return Resource.error(MessagingError.ChatNotFound)
        val chat = chats.chat(chatId) ?: return Resource.error(MessagingError.ChatNotFound)
        val last = chats.lastMessageId(chatId)
        return Resource.success(views.view(chat, last))
    }
}
