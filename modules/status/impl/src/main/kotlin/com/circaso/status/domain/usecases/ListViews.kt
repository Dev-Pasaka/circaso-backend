package com.circaso.status.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.status.api.MomentId
import com.circaso.status.domain.errors.StatusError
import com.circaso.status.domain.models.ViewRecord
import com.circaso.status.domain.repositories.MomentRepository
import java.time.Clock

/**
 * ST6 `GET /moments/{momentId}/views`: the poster only. Someone in the audience gets `status.not_poster`; anyone
 * else `status.moment_not_found`, so a moment not shown to them stays unknown.
 */
class ListViews(private val moments: MomentRepository, private val clock: Clock) {
    suspend operator fun invoke(caller: UserId, momentId: MomentId): Resource<List<ViewRecord>> {
        val moment = moments.findLive(momentId, clock.instant()) ?: return Resource.error(StatusError.MomentNotFound)
        if (moment.posterId != caller) {
            val inAudience = moments.member(momentId, caller) != null
            return Resource.error(if (inAudience) StatusError.NotPoster else StatusError.MomentNotFound)
        }
        return Resource.success(moments.views(momentId))
    }
}
