package com.circaso.assistant.domain.usecases

import com.circaso.assistant.domain.models.ChatMessage
import com.circaso.assistant.domain.models.Role
import com.circaso.assistant.domain.ports.Transactor
import com.circaso.assistant.domain.repositories.AssistantChanges
import com.circaso.assistant.domain.repositories.AssistantStore
import com.circaso.assistant.domain.repositories.AuditEntry
import com.circaso.assistant.domain.repositories.ChatRepository
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import java.util.UUID

/**
 * A6: deletes one exchange, the question and the answer to it, whichever of the two is named. The delete's count
 * decides the answer: of two deletes of one exchange at once, the one that removed nothing is `not_found`, and its
 * audit record is rolled back with it.
 */
class DeleteExchange(
    private val chat: ChatRepository,
    private val transactor: Transactor,
    private val store: AssistantStore,
) {
    suspend operator fun invoke(
        userId: UserId,
        messageId: UUID,
        requestId: String?,
    ): Resource<Unit> =
        transactor.attempt("chat.delete-exchange") {
            val named = chat.find(userId, messageId) ?: return@attempt Resource.error(SharedError.NotFound)
            if (chat.delete(userId, exchangeOf(named)) == 0) return@attempt Resource.error(SharedError.NotFound)
            store.commit(AssistantChanges(audit = listOf(AuditEntry(userId, "EXCHANGE_DELETED", "1", requestId))))
            Resource.success(Unit)
        }

    /** The named message and its other half: a question's answer, or an answer's question. */
    private fun exchangeOf(named: ChatMessage): List<UUID> {
        val other = if (named.role == Role.USER) named.answeredBy else named.replyTo
        return listOfNotNull(named.messageId, other)
    }
}
