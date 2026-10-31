package com.circaso.assistant.domain.usecases

import com.circaso.assistant.domain.errors.AssistantError
import com.circaso.assistant.domain.ports.Transactor
import com.circaso.assistant.domain.repositories.AssistantChanges
import com.circaso.assistant.domain.repositories.AssistantStore
import com.circaso.assistant.domain.repositories.AuditEntry
import com.circaso.assistant.domain.repositories.ConversationRepository
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.util.UUID

/**
 * C4: deletes one conversation and every message in it, with its audit record, in one transaction, as A5 and A6 do:
 * afterwards Asha has no copy of what was said in it. The delete's count decides the answer, so of two deletes at
 * once the one that removed nothing is `not_found` and its audit record is rolled back with it.
 */
class DeleteConversation(
    private val transactor: Transactor,
    private val conversations: ConversationRepository,
    private val store: AssistantStore,
) {
    suspend operator fun invoke(
        userId: UserId,
        conversationId: UUID,
        requestId: String?,
    ): Resource<Unit> =
        transactor.attempt("chat.delete-conversation") {
            if (conversations.deleteConversation(userId, conversationId) == 0) {
                return@attempt Resource.error(AssistantError.ConversationNotFound)
            }
            store.commit(
                AssistantChanges(audit = listOf(AuditEntry(userId, "CONVERSATION_DELETED", "1", requestId))),
            )
            Resource.success(Unit)
        }
}
