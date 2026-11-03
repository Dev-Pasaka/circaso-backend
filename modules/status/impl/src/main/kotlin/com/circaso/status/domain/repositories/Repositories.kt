package com.circaso.status.domain.repositories

import com.circaso.shared.UserId
import com.circaso.status.api.MomentId
import com.circaso.status.domain.models.AudienceMember
import com.circaso.status.domain.models.Moment
import com.circaso.status.domain.models.MomentMedia
import com.circaso.status.domain.models.ViewRecord
import java.time.Instant
import java.util.UUID

/** `moments` and `audience`: what was posted, to whom, and who has seen it. */
interface MomentRepository {
    /**
     * Claims (poster, clientMomentId) and writes the moment with its audience. False when that claim was already
     * taken, in which case nothing was written.
     */
    suspend fun insert(moment: Moment, audience: List<AudienceMember>): Boolean

    suspend fun findByClientId(posterId: UserId, clientMomentId: UUID): Moment?

    /** The moment while it is live at [now]; null once expired or deleted. */
    suspend fun findLive(momentId: MomentId, now: Instant): Moment?

    /** [userId]'s row in the moment's audience, or null when the moment wasn't shown to them. */
    suspend fun member(momentId: MomentId, userId: UserId): AudienceMember?

    /** Everyone the moment was shown to. */
    suspend fun audienceOf(momentId: MomentId): List<UserId>

    /** How many moments [posterId] posted after [since] that are still kept, and the oldest of their times. */
    suspend fun postedSince(posterId: UserId, since: Instant): Pair<Int, Instant?>

    /** Live moments shown to [viewer] with their audience row, by id after [after], at most [limit]. */
    suspend fun visibleTo(
        viewer: UserId,
        after: MomentId?,
        now: Instant,
        limit: Int,
    ): List<Pair<Moment, AudienceMember>>

    /** [posterId]'s own live moments, by id after [after], at most [limit]. */
    suspend fun postedBy(
        posterId: UserId,
        after: MomentId?,
        now: Instant,
        limit: Int,
    ): List<Moment>

    /** How many people have viewed each of [momentIds]; a moment nobody viewed is absent. */
    suspend fun viewCounts(momentIds: Collection<MomentId>): Map<MomentId, Int>

    /**
     * Records [viewer]'s view: `viewed_at` is set the first time only; [reacted] replaces the stored value when given.
     * Null when [viewer] isn't in the audience.
     */
    suspend fun recordView(
        momentId: MomentId,
        viewer: UserId,
        at: Instant,
        reacted: Boolean?,
    ): AudienceMember?

    /** Those who have viewed the moment, in the order they did. */
    suspend fun views(momentId: MomentId): List<ViewRecord>

    /** Deletes the moment and its audience. True when it was there. */
    suspend fun delete(momentId: MomentId): Boolean

    /** Every moment [posterId] still has, for when their account goes. */
    suspend fun allPostedBy(posterId: UserId): List<Moment>

    /** Takes [userId] out of every audience. Returns how many rows went. */
    suspend fun leaveAudiences(userId: UserId): Int
}

/** `media`: one row per encrypted moment file, from ST1 until the sweep deletes it. */
interface MediaRepository {
    suspend fun insert(media: MomentMedia)

    suspend fun find(mediaId: UUID): MomentMedia?

    /** `PENDING` → `READY`, in one conditional update. False when the row was not pending (or is gone). */
    suspend fun markReady(mediaId: UUID): Boolean

    /**
     * Attaches the poster's ready, unattached, unexpired file to [momentId], to expire with it at [expiresAt]. False
     * when any of that isn't so.
     */
    suspend fun attach(
        mediaId: UUID,
        posterId: UserId,
        momentId: MomentId,
        now: Instant,
        expiresAt: Instant,
    ): Boolean

    /** Brings the expiry of every file attached to [momentId] forward to [now], so the sweep deletes it. */
    suspend fun expireFor(momentId: MomentId, now: Instant): List<MomentMedia>

    /** Up to [limit] files past their expiry at [now], oldest first. */
    suspend fun purgeable(now: Instant, limit: Int): List<MomentMedia>

    /** True when the row was there and is now deleted. */
    suspend fun delete(mediaId: UUID): Boolean

    /** Every file [posterId] has, attached or not, for when their account goes. */
    suspend fun allOf(posterId: UserId): List<MomentMedia>
}
