package com.circaso.assistant.domain.usecases

import com.circaso.assistant.domain.errors.AssistantError
import com.circaso.assistant.domain.models.Conversation
import com.circaso.assistant.domain.repositories.ConversationRepository
import com.circaso.assistant.domain.support.ConversationTitles
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import java.time.Clock
import java.util.UUID

/** C3: the user names a conversation. Their title is never replaced by a made one. */
class RenameConversation(private val conversations: ConversationRepository, private val clock: Clock) {
    suspend operator fun invoke(
        userId: UserId,
        conversationId: UUID,
        title: String,
    ): Resource<Conversation> {
        val chosen = title.trim()
        if (chosen.isEmpty() || chosen.length > ConversationTitles.MAX_USER_CHARS) {
            return Resource.error(
                SharedError.Malformed("title", "must be 1 to ${ConversationTitles.MAX_USER_CHARS} characters"),
            )
        }
        val renamed =
            conversations.renameConversation(userId, conversationId, chosen, clock.instant())
                ?: return Resource.error(AssistantError.ConversationNotFound)
        return Resource.success(renamed)
    }
}
