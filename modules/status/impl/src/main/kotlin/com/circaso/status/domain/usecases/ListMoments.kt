package com.circaso.status.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.status.api.MomentId
import com.circaso.status.domain.models.MomentPage
import com.circaso.status.domain.models.MomentView
import com.circaso.status.domain.repositories.MomentRepository
import com.circaso.status.domain.support.MomentReach
import java.time.Clock

/**
 * ST4 `GET /moments`: every live moment shown to the caller, plus the caller's own, by moment id (so oldest first
 * within a poster) after [after]. The two lists are read a page each and merged; `next` is the last id of a full
 * page. A poster blocked either way since posting is left out without a word.
 */
class ListMoments(private val moments: MomentRepository, private val reach: MomentReach, private val clock: Clock) {
    suspend operator fun invoke(
        caller: UserId,
        after: MomentId?,
        limit: Int,
    ): Resource<MomentPage> {
        val now = clock.instant()
        val shown =
            moments.visibleTo(caller, after, now, limit + 1).map { (moment, member) ->
                MomentView(
                    moment = moment,
                    keyCopy = member.keyCopy,
                    mine = false,
                    viewed = member.viewedAt != null,
                    reacted = member.reacted,
                    viewCount = null,
                )
            }
        val own =
            moments.postedBy(caller, after, now, limit + 1).map { moment ->
                MomentView(moment, keyCopy = null, mine = true, viewed = false, reacted = false, viewCount = 0)
            }
        val merged = (shown + own).sortedBy { it.moment.momentId.value }.take(limit + 1)
        val page = merged.take(limit)
        val next = if (merged.size > limit) page.last().moment.momentId else null

        return Resource.success(MomentPage(withCounts(visible(caller, page)), next))
    }

    private suspend fun visible(caller: UserId, page: List<MomentView>): List<MomentView> {
        val posters = page.filterNot { it.mine }.map { it.moment.posterId }.toSet()
        val hidden = posters.filterNot { reach.stillVisible(caller, it) }.toSet()
        return page.filterNot { it.moment.posterId in hidden }
    }

    private suspend fun withCounts(page: List<MomentView>): List<MomentView> {
        val mine = page.filter { it.mine }.map { it.moment.momentId }
        if (mine.isEmpty()) return page
        val counts = moments.viewCounts(mine)
        return page.map { if (it.mine) it.copy(viewCount = counts[it.moment.momentId] ?: 0) else it }
    }
}
