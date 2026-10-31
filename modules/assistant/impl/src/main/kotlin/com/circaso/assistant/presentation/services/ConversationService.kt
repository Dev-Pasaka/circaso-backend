package com.circaso.assistant.presentation.services

import com.circaso.assistant.domain.models.ChatPage
import com.circaso.assistant.domain.models.Conversation
import com.circaso.assistant.domain.models.ConversationPage
import com.circaso.assistant.domain.usecases.CreateConversation
import com.circaso.assistant.domain.usecases.DeleteConversation
import com.circaso.assistant.domain.usecases.ListConversationMessages
import com.circaso.assistant.domain.usecases.ListConversations
import com.circaso.assistant.domain.usecases.RenameConversation
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.util.UUID

/** The user's conversations with Asha: listing, starting, renaming and deleting them, and reading one (C1 to C5). */
class ConversationService(
    private val listConversations: ListConversations,
    private val createConversation: CreateConversation,
    private val renameConversation: RenameConversation,
    private val deleteConversation: DeleteConversation,
    private val listConversationMessages: ListConversationMessages,
) {
    suspend fun list(
        userId: UserId,
        cursor: String?,
        limit: Int,
    ): Resource<ConversationPage> = listConversations(userId, cursor, limit)

    suspend fun create(userId: UserId, title: String?): Resource<Conversation> = createConversation(userId, title)

    suspend fun rename(
        userId: UserId,
        conversationId: UUID,
        title: String,
    ): Resource<Conversation> = renameConversation(userId, conversationId, title)

    suspend fun delete(
        userId: UserId,
        conversationId: UUID,
        requestId: String?,
    ): Resource<Unit> = deleteConversation(userId, conversationId, requestId)

    suspend fun messages(
        userId: UserId,
        conversationId: UUID,
        cursor: String?,
        limit: Int,
    ): Resource<ChatPage> = listConversationMessages(userId, conversationId, cursor, limit)
}
