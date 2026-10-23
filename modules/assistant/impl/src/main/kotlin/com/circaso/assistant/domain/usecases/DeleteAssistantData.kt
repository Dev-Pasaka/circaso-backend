package com.circaso.assistant.domain.usecases

import com.circaso.assistant.domain.repositories.AssistantChanges
import com.circaso.assistant.domain.repositories.AssistantStore
import com.circaso.assistant.domain.repositories.AuditEntry
import com.circaso.identity.api.AccountDeleted
import com.circaso.shared.Resource

/** `identity.AccountDeleted`: the chat goes with the account. Audit records of actions and usage counts remain. */
class DeleteAssistantData(private val store: AssistantStore) {
    suspend operator fun invoke(event: AccountDeleted): Resource<Unit> {
        store.deleteChat(event.userId)
        store.commit(AssistantChanges(audit = listOf(AuditEntry(event.userId, "ACCOUNT_DELETED", "chat removed", null))))
        return Resource.success(Unit)
    }
}
