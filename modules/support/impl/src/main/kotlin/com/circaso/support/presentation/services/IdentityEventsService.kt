package com.circaso.support.presentation.services

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.support.domain.usecases.RemoveAccountTickets

/** What support does with identity's events: a deleted account's tickets go with it. */
class IdentityEventsService(private val removeAccountTickets: RemoveAccountTickets) {
    suspend fun accountDeleted(userId: UserId): Resource<Unit> = removeAccountTickets(userId)
}
