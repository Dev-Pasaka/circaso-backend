package com.circaso.status.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.status.api.MomentId
import com.circaso.status.api.MomentViewedFrame
import com.circaso.status.domain.errors.StatusError
import com.circaso.status.domain.models.ViewOutcome
import com.circaso.status.domain.ports.FrameSender
import com.circaso.status.domain.ports.Transactor
import com.circaso.status.domain.repositories.MomentRepository
import com.circaso.status.domain.support.MomentReach
import java.time.Clock

/**
 * ST5 `POST /moments/{momentId}/view`: the audience only. The first call records the view; later ones change
 * `reacted` when they carry it. The poster gets `status.viewed` whenever something changed, never for a repeat.
 */
class ViewMoment(
    private val moments: MomentRepository,
    private val transactor: Transactor,
    private val reach: MomentReach,
    private val frames: FrameSender,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        caller: UserId,
        momentId: MomentId,
        reacted: Boolean?,
    ): Resource<ViewOutcome> {
        val now = clock.instant()
        val moment = moments.findLive(momentId, now) ?: return Resource.error(StatusError.MomentNotFound)
        if (moment.posterId == caller || !reach.stillVisible(caller, moment.posterId)) {
            return Resource.error(StatusError.MomentNotFound)
        }

        val outcome =
            transactor.run("moments.view") {
                val before = moments.member(momentId, caller) ?: return@run null
                val after = moments.recordView(momentId, caller, now, reacted) ?: return@run null
                val viewedAt = after.viewedAt ?: now
                ViewOutcome(viewedAt, after.reacted, before.viewedAt == null || before.reacted != after.reacted)
            } ?: return Resource.error(StatusError.MomentNotFound)

        if (outcome.changed) {
            frames.send(listOf(moment.posterId), MomentViewedFrame(momentId, caller, outcome.viewedAt, outcome.reacted))
        }
        return Resource.success(outcome)
    }
}
