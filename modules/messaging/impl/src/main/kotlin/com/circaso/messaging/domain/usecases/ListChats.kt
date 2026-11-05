package com.circaso.messaging.domain.usecases

import com.circaso.messaging.domain.models.ChatView
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.support.ChatViews
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/**
 * M6 `GET /chats`: every chat the user is in, with members' public profiles and the newest message id the server
 * still holds (null after 30 days without a message). The app sorts and counts unread itself.
 *
 * Two hops: one messaging transaction for the chats, their heads and their members, then users' one profile read
 * for every member of all of them.
 */
class ListChats(private val chats: ChatRepository, private val views: ChatViews) {
    suspend operator fun invoke(userId: UserId): Resource<List<ChatView>> =
        Resource.success(views.views(chats.chatListWithMembers(userId)))
}
