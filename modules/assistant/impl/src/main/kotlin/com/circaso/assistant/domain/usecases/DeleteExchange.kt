package com.circaso.assistant.domain.usecases

import com.circaso.assistant.domain.models.Role
import com.circaso.assistant.domain.repositories.AssistantChanges
import com.circaso.assistant.domain.repositories.AssistantStore
import com.circaso.assistant.domain.repositories.AuditEntry
import com.circaso.assistant.domain.repositories.ChatRepository
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import java.util.UUID

/** A6: deletes one exchange, the question and the answer to it, whichever of the two is named. */
class DeleteExchange(private val chat: ChatRepository, private val store: AssistantStore) {
    suspend operator fun invoke(
        userId: UserId,
        messageId: UUID,
        requestId: String?,
    ): Resource<Unit> {
        val named = chat.find(userId, messageId) ?: return Resource.error(SharedError.NotFound)
        val question = if (named.role == Role.USER) named else named.replyTo?.let { chat.find(userId, it) }
        val answer = if (named.role == Role.ASHA) named else named.answeredBy?.let { chat.find(userId, it) }
        store.commit(
            AssistantChanges(
                deletedMessages = listOfNotNull(question, answer),
                audit = listOf(AuditEntry(userId, "EXCHANGE_DELETED", "1", requestId)),
            ),
        )
        return Resource.success(Unit)
    }
}
