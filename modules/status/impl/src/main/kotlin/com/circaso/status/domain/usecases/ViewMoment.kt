package com.circaso.status.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.status.api.MomentId
import com.circaso.status.api.MomentViewedFrame
import com.circaso.status.domain.errors.StatusError
import com.circaso.status.domain.models.ReactionChange
import com.circaso.status.domain.models.ViewOutcome
import com.circaso.status.domain.ports.FrameSender
import com.circaso.status.domain.ports.Transactor
import com.circaso.status.domain.repositories.MomentRepository
import com.circaso.status.domain.support.MomentReach
import com.circaso.status.domain.support.isReaction
import com.circaso.users.api.Profiles
import java.time.Clock

/**
 * ST5 `POST /moments/{momentId}/view`: the audience only. The first call records the view; later ones set, change
 * or clear the caller's reaction (one emoji) when they carry it. The poster gets `status.viewed` whenever something
 * changed, never for a repeat; a frame that adds or changes a reaction carries the viewer's name, which the gateway
 * turns into a push (Pasaka, 2026-11-03).
 */
class ViewMoment(
    private val moments: MomentRepository,
    private val transactor: Transactor,
    private val reach: MomentReach,
    private val frames: FrameSender,
    private val clock: Clock,
    private val profiles: Profiles? = null,
) {
    suspend operator fun invoke(
        caller: UserId,
        momentId: MomentId,
        reaction: ReactionChange,
    ): Resource<ViewOutcome> {
        if (reaction is ReactionChange.Set && !isReaction(reaction.emoji)) {
            return Resource.error(StatusError.ReactionInvalid)
        }
        val now = clock.instant()
        val moment = moments.findLive(momentId, now) ?: return Resource.error(StatusError.MomentNotFound)
        if (moment.posterId == caller || !reach.stillVisible(caller, moment.posterId)) {
            return Resource.error(StatusError.MomentNotFound)
        }

        val (outcome, reactedAnew) =
            transactor.run("moments.view") {
                val before = moments.member(momentId, caller) ?: return@run null
                val after = moments.recordView(momentId, caller, now, reaction) ?: return@run null
                val viewedAt = after.viewedAt ?: now
                val changed = before.viewedAt == null || before.reaction != after.reaction
                ViewOutcome(viewedAt, after.reaction, changed) to
                    (after.reaction != null && after.reaction != before.reaction)
            } ?: return Resource.error(StatusError.MomentNotFound)

        if (outcome.changed) {
            val name = if (reactedAnew) displayName(caller) else null
            frames.send(
                listOf(moment.posterId),
                MomentViewedFrame(
                    momentId = momentId,
                    viewerId = caller,
                    viewedAt = outcome.viewedAt,
                    reacted = outcome.reacted,
                    reaction = outcome.reaction,
                    viewerName = name,
                ),
            )
        }
        return Resource.success(outcome)
    }

    /** Best effort: without a name the frame still goes, it just isn't pushed. */
    private suspend fun displayName(caller: UserId): String? =
        runCatching { profiles?.get(setOf(caller))?.get(caller)?.displayName }.getOrNull()
}
