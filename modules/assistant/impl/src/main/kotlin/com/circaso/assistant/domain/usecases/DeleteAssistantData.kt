package com.circaso.assistant.domain.usecases

import com.circaso.assistant.domain.ports.Transactor
import com.circaso.assistant.domain.repositories.AssistantChanges
import com.circaso.assistant.domain.repositories.AssistantStore
import com.circaso.assistant.domain.repositories.AuditEntry
import com.circaso.identity.api.AccountDeleted
import com.circaso.shared.Resource

/**
 * `identity.AccountDeleted`: the chat and the entitlement go with the account, with the audit record that says so, in
 * one transaction. Audit records of actions and usage counts remain; they hold no conversation text.
 */
class DeleteAssistantData(private val transactor: Transactor, private val store: AssistantStore) {
    suspend operator fun invoke(event: AccountDeleted): Resource<Unit> {
        transactor.run("assistant.erase") {
            store.erase(event.userId)
            store.commit(
                AssistantChanges(audit = listOf(AuditEntry(event.userId, "ACCOUNT_DELETED", "chat removed", null))),
            )
        }
        return Resource.success(Unit)
    }
}
