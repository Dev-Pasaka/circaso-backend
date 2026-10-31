package com.circaso.assistant.domain.usecases

import com.circaso.assistant.domain.models.Conversation
import com.circaso.assistant.domain.models.ConversationCursor
import com.circaso.assistant.domain.models.ConversationPage
import com.circaso.assistant.domain.repositories.ConversationRepository
import com.circaso.assistant.domain.support.ConversationTitler
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import java.time.Instant
import java.util.UUID

/**
 * C1: the user's conversations with Asha, newest first by last message, paged by an opaque cursor. Conversation 1
 * from V2 gets its title here the first time it is listed (its messages are sealed, so the migration couldn't).
 */
class ListConversations(private val conversations: ConversationRepository, private val titler: ConversationTitler) {
    suspend operator fun invoke(
        userId: UserId,
        cursor: String?,
        limit: Int,
    ): Resource<ConversationPage> {
        val after =
            cursor?.let {
                cursorOf(it) ?: return Resource.error(SharedError.Malformed("cursor", "invalid"))
            }
        val size = limit.coerceIn(1, MAX_LIMIT)
        val rows = conversations.conversations(userId, after, size + 1)
        val shown = rows.take(size).map { titler.titled(it) }
        val next = rows.getOrNull(size - 1)?.takeIf { rows.size > size }?.let(::nextCursorOf)
        return Resource.success(ConversationPage(shown, next))
    }

    /** `<lastMessageAt>_<conversationId>`: where the next page starts, after [last]. */
    private fun nextCursorOf(last: Conversation): String = "${last.lastMessageAt}$SEPARATOR${last.conversationId}"

    /** The last row of the previous page, from its cursor. */
    private fun cursorOf(raw: String): ConversationCursor? {
        val at = raw.substringBefore(SEPARATOR, "")
        val id = raw.substringAfter(SEPARATOR, "")
        return runCatching { ConversationCursor(Instant.parse(at), UUID.fromString(id)) }.getOrNull()
    }

    private companion object {
        const val MAX_LIMIT = 100
        const val SEPARATOR = "_"
    }
}
