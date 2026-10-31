package com.circaso.assistant.domain.support

import com.circaso.assistant.domain.errors.AssistantError
import com.circaso.assistant.domain.models.Conversation
import com.circaso.assistant.domain.repositories.ConversationRepository
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Clock
import java.util.UUID

/**
 * Which conversation a turn goes to (A2, A9, A11): the one named, if it is the user's; with none named, the user's
 * most recent one, or a new one when they have none, so an app that doesn't know about conversations keeps one thread.
 */
class ConversationResolver(
    private val conversations: ConversationRepository,
    private val ids: OrderedIds,
    private val clock: Clock,
) {
    suspend fun resolve(userId: UserId, requested: UUID?): Resource<Conversation> {
        if (requested != null) {
            val named = conversations.conversation(userId, requested)
            return if (named == null) Resource.error(AssistantError.ConversationNotFound) else Resource.success(named)
        }
        val latest = conversations.latestConversation(userId)
        if (latest != null) return Resource.success(latest)
        return Resource.success(open(userId))
    }

    /** A new, empty, untitled conversation. */
    suspend fun open(userId: UserId, title: String? = null): Conversation {
        val now = clock.instant()
        val conversation = Conversation(userId, ids.next(), title, now, now, now)
        conversations.addConversation(conversation)
        return conversation
    }
}
