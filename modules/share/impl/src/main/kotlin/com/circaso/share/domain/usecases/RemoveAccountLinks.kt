package com.circaso.share.domain.usecases

import com.circaso.share.domain.repositories.LinkRepository
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** A deleted account's links all go at once, snapshot and row (identity's `AccountStatusChanged` and `AccountDeleted`). */
class RemoveAccountLinks(private val links: LinkRepository) {
    suspend operator fun invoke(userId: UserId): Resource<Unit> {
        links.deleteOwnedBy(userId)
        return Resource.success(Unit)
    }
}
