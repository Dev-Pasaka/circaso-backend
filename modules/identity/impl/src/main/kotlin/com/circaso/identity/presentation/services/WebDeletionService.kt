package com.circaso.identity.presentation.services

import com.circaso.identity.domain.models.WebDeletionCommand
import com.circaso.identity.domain.models.WebDeletionOutcome
import com.circaso.identity.domain.usecases.RequestWebAccountDeletion
import com.circaso.shared.Resource

/** The website's account deletion page (I17). */
class WebDeletionService(private val requestWebAccountDeletion: RequestWebAccountDeletion) {
    suspend fun request(command: WebDeletionCommand): Resource<WebDeletionOutcome> = requestWebAccountDeletion(command)
}
