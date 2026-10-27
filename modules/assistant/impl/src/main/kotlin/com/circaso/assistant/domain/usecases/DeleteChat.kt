package com.circaso.assistant.domain.usecases

import com.circaso.assistant.domain.ports.Transactor
import com.circaso.assistant.domain.repositories.AssistantChanges
import com.circaso.assistant.domain.repositories.AssistantStore
import com.circaso.assistant.domain.repositories.AuditEntry
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/**
 * A5: deletes the whole history with Asha, with its audit record, in one transaction. Afterwards she has no copy of
 * what was said. What remains holds no conversation text: audit records of what was done, and usage counts for billing.
 */
class DeleteChat(private val transactor: Transactor, private val store: AssistantStore) {
    suspend operator fun invoke(userId: UserId, requestId: String?): Resource<Unit> {
        transactor.run("chat.delete") {
            store.deleteChat(userId)
            store.commit(AssistantChanges(audit = listOf(AuditEntry(userId, "CHAT_DELETED", "all", requestId))))
        }
        return Resource.success(Unit)
    }
}
