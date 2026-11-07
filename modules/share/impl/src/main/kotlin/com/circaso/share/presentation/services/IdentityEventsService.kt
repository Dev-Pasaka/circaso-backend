package com.circaso.share.presentation.services

import com.circaso.share.domain.usecases.RemoveAccountLinks
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** What share does with identity's events: a deleted account's links all go at once. */
class IdentityEventsService(private val removeAccountLinks: RemoveAccountLinks) {
    suspend fun accountDeleted(userId: UserId): Resource<Unit> = removeAccountLinks(userId)
}
