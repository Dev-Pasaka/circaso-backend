package com.circaso.status.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.status.domain.repositories.MediaRepository
import com.circaso.status.domain.repositories.MomentRepository
import com.circaso.status.domain.support.MomentRemoval

/**
 * An account was deleted (identity's `AccountStatusChanged` to `DELETED`, and again at `AccountDeleted`): its moments
 * go at once, each audience told with `status.deleted`, every file it uploaded with them, and the person leaves every
 * audience they were in. Safe to run twice.
 */
class RemoveAccountMoments(
    private val moments: MomentRepository,
    private val media: MediaRepository,
    private val removal: MomentRemoval,
) {
    suspend operator fun invoke(userId: UserId): Resource<Unit> {
        moments.allPostedBy(userId).forEach { removal.remove(it) }
        media.allOf(userId).forEach { removal.deleteFile(it) }
        moments.leaveAudiences(userId)
        return Resource.success(Unit)
    }
}
