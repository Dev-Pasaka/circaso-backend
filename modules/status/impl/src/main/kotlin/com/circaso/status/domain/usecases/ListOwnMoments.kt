package com.circaso.status.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.status.domain.models.OwnMoment
import com.circaso.status.domain.repositories.MomentRepository
import java.time.Clock

/**
 * Asha's `GET /agent/status/moments` (decision 2026-11-05): the caller's own live moments, newest first, with how many
 * people viewed and reacted to each. Never the sealed text or the media, which only phones can open, and never who
 * viewed: the counts answer "how is my moment doing" without naming anyone.
 */
class ListOwnMoments(private val moments: MomentRepository, private val clock: Clock) {
    suspend operator fun invoke(caller: UserId): Resource<List<OwnMoment>> {
        val own = moments.postedBy(caller, after = null, now = clock.instant(), limit = MAX_MOMENTS)
        if (own.isEmpty()) return Resource.success(emptyList())
        val counts = moments.viewCounts(own.map { it.momentId })
        val listed =
            own
                .sortedByDescending { it.postedAt }
                .map {
                    val count = counts[it.momentId]
                    OwnMoment(it, views = count?.views ?: 0, reactions = count?.reactions ?: 0)
                }
        return Resource.success(listed)
    }

    private companion object {
        /** A day of moments is a handful; this only caps an odd poster. */
        const val MAX_MOMENTS = 50
    }
}
