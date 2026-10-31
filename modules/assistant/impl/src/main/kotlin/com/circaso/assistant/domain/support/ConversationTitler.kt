package com.circaso.assistant.domain.support

import com.circaso.assistant.domain.models.Conversation
import com.circaso.assistant.domain.models.Role
import com.circaso.assistant.domain.repositories.ChatRepository
import com.circaso.assistant.domain.repositories.ConversationRepository
import com.circaso.shared.UserId
import java.util.UUID

/**
 * Titles a conversation from its first question ([ConversationTitles]). A new conversation is titled with its first
 * exchange, in the transaction that saves it; conversation 1 from V2 (whose sealed messages SQL can't read) the first
 * time the app reads it. A title already there, the user's or a first one, is never replaced.
 */
class ConversationTitler(private val chat: ChatRepository, private val conversations: ConversationRepository) {
    /**
     * The title its first message gives the conversation: [asking] when it has no message yet (the question being
     * saved now), [ConversationTitles.EARLIER] when the first isn't a question; null for an empty one.
     */
    suspend fun firstTitle(
        userId: UserId,
        conversationId: UUID,
        asking: String? = null,
    ): String? {
        val first = chat.oldestIn(userId, conversationId)
        return when {
            first == null -> asking?.let(ConversationTitles::from)
            first.role == Role.USER -> ConversationTitles.from(first.text)
            else -> ConversationTitles.EARLIER
        }
    }

    /** [conversation] with its title: one with messages but no title yet is titled now. */
    suspend fun titled(conversation: Conversation): Conversation {
        if (conversation.title != null) return conversation
        val userId = conversation.userId
        val id = conversation.conversationId
        val title = firstTitle(userId, id) ?: return conversation
        if (conversations.titleConversation(userId, id, title)) return conversation.copy(title = title)
        // titled by someone else in between (the user, or a turn): theirs stands
        return conversations.conversation(userId, id) ?: conversation
    }
}
