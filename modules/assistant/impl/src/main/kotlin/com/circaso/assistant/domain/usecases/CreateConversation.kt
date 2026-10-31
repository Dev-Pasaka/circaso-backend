package com.circaso.assistant.domain.usecases

import com.circaso.assistant.domain.models.Conversation
import com.circaso.assistant.domain.support.ConversationResolver
import com.circaso.assistant.domain.support.ConversationTitles
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId

/**
 * C2: a brand new, empty conversation with Asha. It becomes the user's most recent, so an A2 without a conversation
 * goes to it too. Without a [title] it is titled from its first question.
 */
class CreateConversation(private val conversations: ConversationResolver) {
    suspend operator fun invoke(userId: UserId, title: String?): Resource<Conversation> {
        val chosen = title?.trim()
        if (chosen != null && (chosen.isEmpty() || chosen.length > ConversationTitles.MAX_USER_CHARS)) {
            return Resource.error(
                SharedError.Malformed("title", "must be 1 to ${ConversationTitles.MAX_USER_CHARS} characters"),
            )
        }
        return Resource.success(conversations.open(userId, chosen))
    }
}
