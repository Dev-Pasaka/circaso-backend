package com.circaso.status.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.status.api.MomentId
import com.circaso.status.domain.errors.StatusError
import com.circaso.status.domain.repositories.MomentRepository
import com.circaso.status.domain.support.MomentRemoval
import java.time.Clock

/**
 * ST7 `DELETE /moments/{momentId}`: the poster only. The moment, its views and its file go; the audience gets
 * `status.deleted`. Someone in the audience gets `status.not_poster`, anyone else `status.moment_not_found`.
 */
class DeleteMoment(
    private val moments: MomentRepository,
    private val removal: MomentRemoval,
    private val clock: Clock,
) {
    suspend operator fun invoke(caller: UserId, momentId: MomentId): Resource<Unit> {
        val moment = moments.findLive(momentId, clock.instant()) ?: return Resource.error(StatusError.MomentNotFound)
        if (moment.posterId != caller) {
            val inAudience = moments.member(momentId, caller) != null
            return Resource.error(if (inAudience) StatusError.NotPoster else StatusError.MomentNotFound)
        }
        if (!removal.remove(moment)) return Resource.error(StatusError.MomentNotFound)
        return Resource.success(Unit)
    }
}
