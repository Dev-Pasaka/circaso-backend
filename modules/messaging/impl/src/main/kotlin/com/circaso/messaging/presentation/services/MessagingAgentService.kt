package com.circaso.messaging.presentation.services

import com.circaso.messaging.domain.models.ChatView
import com.circaso.messaging.domain.usecases.ListChats
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** What Asha's read-only agent route reads (decision 2026-10-31): the caller's own chat list, reads only. */
class MessagingAgentService(private val listChats: ListChats) {
    suspend fun listChats(userId: UserId): Resource<List<ChatView>> = listChats.invoke(userId)
}
