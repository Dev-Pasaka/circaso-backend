package com.circaso.status.presentation.services

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.status.domain.usecases.RemoveAccountMoments

/** What status does with identity's events: a deleted account's moments go at once. */
class IdentityEventsService(private val removeAccountMoments: RemoveAccountMoments) {
    suspend fun accountDeleted(userId: UserId): Resource<Unit> = removeAccountMoments(userId)
}
