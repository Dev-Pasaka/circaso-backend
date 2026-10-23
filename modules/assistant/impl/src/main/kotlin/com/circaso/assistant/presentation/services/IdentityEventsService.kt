package com.circaso.assistant.presentation.services

import com.circaso.assistant.domain.usecases.DeleteAssistantData
import com.circaso.identity.api.AccountDeleted
import com.circaso.shared.Resource

/** Identity's events, which the assistant reacts to. */
class IdentityEventsService(private val deleteAssistantData: DeleteAssistantData) {
    suspend fun accountDeleted(event: AccountDeleted): Resource<Unit> = deleteAssistantData(event)
}
