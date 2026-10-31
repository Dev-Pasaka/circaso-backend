package com.circaso.assistant.domain.usecases

import com.circaso.assistant.domain.errors.AssistantError
import com.circaso.assistant.domain.models.ChatPage
import com.circaso.assistant.domain.repositories.ChatRepository
import com.circaso.assistant.domain.repositories.ConversationRepository
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import java.util.UUID

/** C5: one conversation's messages, newest first, paged by an opaque cursor as A1 is. */
class ListConversationMessages(private val conversations: ConversationRepository, private val chat: ChatRepository) {
    suspend operator fun invoke(
        userId: UserId,
        conversationId: UUID,
        cursor: String?,
        limit: Int,
    ): Resource<ChatPage> {
        val before =
            cursor?.let {
                runCatching { UUID.fromString(it) }.getOrNull()
                    ?: return Resource.error(SharedError.Malformed("cursor", "invalid"))
            }
        conversations.conversation(userId, conversationId)
            ?: return Resource.error(AssistantError.ConversationNotFound)
        val size = limit.coerceIn(1, MAX_LIMIT)
        val rows = chat.listIn(userId, conversationId, before, size + 1)
        val shown = rows.take(size)
        return Resource.success(ChatPage(shown, if (rows.size > size) shown.last().messageId.toString() else null))
    }

    private companion object {
        const val MAX_LIMIT = 100
    }
}
