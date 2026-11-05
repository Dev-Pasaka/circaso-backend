package com.circaso.spaces.domain.repositories

import com.circaso.shared.UserId
import com.circaso.spaces.api.PostId
import com.circaso.spaces.api.SpaceId
import com.circaso.spaces.domain.models.Ban
import com.circaso.spaces.domain.models.Invite
import com.circaso.spaces.domain.models.JoinAsk
import com.circaso.spaces.domain.models.Membership
import com.circaso.spaces.domain.models.MembershipPayment
import com.circaso.spaces.domain.models.Post
import com.circaso.spaces.domain.models.ReactionCount
import com.circaso.spaces.domain.models.RemovedBy
import com.circaso.spaces.domain.models.Report
import com.circaso.spaces.domain.models.ReportAction
import com.circaso.spaces.domain.models.Space
import com.circaso.spaces.domain.models.SpaceMedia
import com.circaso.spaces.domain.models.Topic
import java.time.Instant
import java.util.UUID

/** `spaces.spaces`: one row per space; the live handle is unique (`spaces_handle_key`). */
interface SpaceRepository {
    /** Inserts [space] unless its handle is taken or its create key was used; true if this call inserted it. */
    suspend fun insert(space: Space): Boolean

    suspend fun find(spaceId: SpaceId): Space?

    suspend fun findAll(spaceIds: Collection<SpaceId>): List<Space>

    /** The space holding [handle] (a deleted space has given its handle up). */
    suspend fun byHandle(handle: String): Space?

    suspend fun byCreateKey(ownerId: UserId, createKey: String): Space?

    /** Of [handles], those a space holds. */
    suspend fun takenHandles(handles: Collection<String>): Set<String>

    /** Writes [new] if the row is still at [old]'s version (which it moves on by one); null when it wasn't. */
    suspend fun update(old: Space, new: Space): Space?

    /** When [ownerId] created spaces since [since] (the 3-a-day rule). */
    suspend fun createdSince(ownerId: UserId, since: Instant): List<Instant>

    suspend fun ownedBy(ownerId: UserId): List<Space>

    /** Live public and members-only spaces matching [text] (full text, or prefixes), most members first. */
    suspend fun search(
        text: String,
        topic: Topic?,
        offset: Int,
        limit: Int,
    ): List<Space>

    /** Live public and members-only spaces whose handle starts with [prefix] (not equal to it). */
    suspend fun handlesStartingWith(
        prefix: String,
        offset: Int,
        limit: Int,
    ): List<Space>

    /** Live discoverable spaces, most members first; only [country]'s when it is given. */
    suspend fun popular(
        country: String?,
        topic: Topic?,
        exclude: Set<SpaceId>,
        offset: Int,
        limit: Int,
    ): List<Space>

    /** Counts the space's members again (a membership that ended doesn't count) and stores it; the new count. */
    suspend fun refreshMemberCount(spaceId: SpaceId): Int

    suspend fun touchActivity(spaceId: SpaceId, at: Instant)
}

/** `spaces.members`: one row per person in a space, the owner's included. */
interface MemberRepository {
    suspend fun find(spaceId: SpaceId, userId: UserId): Membership?

    /** Inserts [membership] unless the person already has a row; true if this call inserted it. */
    suspend fun insert(membership: Membership): Boolean

    suspend fun save(membership: Membership)

    suspend fun delete(spaceId: SpaceId, userId: UserId): Boolean

    /** Every space [userId] has a row in, ended memberships too. */
    suspend fun ofUser(userId: UserId): List<Membership>

    /** Moderators, admins and the owner. */
    suspend fun team(spaceId: SpaceId): List<Membership>

    /** Everyone whose membership hasn't ended. */
    suspend fun active(spaceId: SpaceId): List<Membership>

    /** Of [userIds], those in the space (ended memberships left out). */
    suspend fun among(spaceId: SpaceId, userIds: Collection<UserId>): List<Membership>

    /** The spaces any of [userIds] are in, and who of them is in each. */
    suspend fun spacesOf(userIds: Collection<UserId>): Map<SpaceId, List<UserId>>

    /** A page of the active members, newest first, from [offset]; [mutedAfter] keeps only the muted ones. */
    suspend fun page(
        spaceId: SpaceId,
        mutedAfter: Instant?,
        offset: Int,
        limit: Int,
    ): List<Membership>

    /** Paid memberships whose state should move now: `ACTIVE` past `paid_until`, `GRACE` past its 3 days. */
    suspend fun paidDue(
        graceStartedBefore: Instant,
        now: Instant,
        limit: Int,
    ): List<Membership>

    /** Paid `ACTIVE` memberships that reach [monthlyBefore] / [yearlyBefore] and haven't been reminded for it. */
    suspend fun reminderDue(
        monthlyBefore: Instant,
        yearlyBefore: Instant,
        limit: Int,
    ): List<Membership>

    /** Moves the read marker to [postId] when it is later than the one held. */
    suspend fun markRead(
        spaceId: SpaceId,
        userId: UserId,
        postId: UUID,
    )

    /** Every row of [userId]'s, for account deletion. */
    suspend fun deleteAllOf(userId: UserId)

    /** The space stopped being members-only: ended memberships go, the rest become free ones. */
    suspend fun clearPaid(spaceId: SpaceId)

    /** The space became members-only: everyone already in stays, for free and for good (`complimentary`). */
    suspend fun makeComplimentary(spaceId: SpaceId)
}

interface BanRepository {
    suspend fun find(spaceId: SpaceId, userId: UserId): Ban?

    suspend fun insert(ban: Ban): Boolean

    suspend fun delete(spaceId: SpaceId, userId: UserId): Boolean

    suspend fun ofSpace(spaceId: SpaceId): List<Ban>

    /** The spaces [userId] is banned from (left out of their suggestions). */
    suspend fun spacesBanning(userId: UserId): Set<SpaceId>
}

interface RequestRepository {
    suspend fun find(spaceId: SpaceId, userId: UserId): JoinAsk?

    suspend fun save(request: JoinAsk)

    suspend fun pending(spaceId: SpaceId): List<JoinAsk>
}

interface InviteRepository {
    suspend fun find(inviteId: UUID): Invite?

    suspend fun of(spaceId: SpaceId, inviteeId: UserId): Invite?

    /** One row per (space, invitee): inviting again replaces it. */
    suspend fun save(invite: Invite)

    /** [inviteeId]'s invites still waiting for an answer at [now]. */
    suspend fun liveFor(inviteeId: UserId, now: Instant): List<Invite>

    /** The space's invites sent since [since], newest first. */
    suspend fun ofSpace(spaceId: SpaceId, since: Instant): List<Invite>
}

interface PaymentRepository {
    suspend fun insert(payment: MembershipPayment)

    suspend fun find(paymentId: UUID): MembershipPayment?

    /** Marks it confirmed; false when it already was (so a repeated event changes nothing). */
    suspend fun confirm(paymentId: UUID, at: Instant): Boolean
}

/** `spaces.posts` and `spaces.reactions`. */
interface PostRepository {
    /** Inserts [post] unless its author already used its `clientPostId`; true if this call inserted it. */
    suspend fun insert(post: Post): Boolean

    suspend fun byClientId(authorId: UserId, clientPostId: UUID): Post?

    suspend fun find(postId: PostId): Post?

    suspend fun findAll(postIds: Collection<PostId>): List<Post>

    /** Newest first, before [before] (or the newest). */
    suspend fun before(
        spaceId: SpaceId,
        before: PostId?,
        limit: Int,
    ): List<Post>

    /** Oldest first, after [after]. */
    suspend fun after(
        spaceId: SpaceId,
        after: PostId,
        limit: Int,
    ): List<Post>

    suspend fun latest(spaceId: SpaceId): Post?

    /** How many posts [authorId] wrote since [since], and the oldest of them. */
    suspend fun countSince(authorId: UserId, since: Instant): Pair<Int, Instant?>

    /** Tombstones the post: text and files gone. Null when there is no such post. */
    suspend fun remove(
        postId: PostId,
        by: RemovedBy,
        at: Instant,
    ): Post?

    /** Posts after [after] (all when null), at most [cap]. */
    suspend fun countAfter(
        spaceId: SpaceId,
        after: UUID?,
        cap: Int,
    ): Int

    /** Sets (or with null clears) [userId]'s one reaction on the post. */
    suspend fun react(
        postId: PostId,
        userId: UserId,
        emoji: String?,
        at: Instant,
    )

    suspend fun reactionCounts(postIds: Collection<PostId>): Map<PostId, List<ReactionCount>>

    suspend fun myReactions(postIds: Collection<PostId>, userId: UserId): Map<PostId, String>
}

interface MediaRepository {
    suspend fun insert(media: SpaceMedia)

    suspend fun find(mediaId: UUID): SpaceMedia?

    suspend fun findAll(mediaIds: Collection<UUID>): List<SpaceMedia>

    suspend fun markReady(mediaId: UUID, sizeBytes: Long): Boolean

    /** Marks [mediaIds] used if each is [ownerId]'s and ready; false (nothing changed) otherwise. */
    suspend fun use(mediaIds: Collection<UUID>, ownerId: UserId): Boolean

    /** Files never used whose upload window passed, for the sweep. */
    suspend fun expired(now: Instant, limit: Int): List<SpaceMedia>

    suspend fun delete(mediaId: UUID)
}

interface ReportRepository {
    /** Inserts [report] unless [Report.reporterId] has one open on the same target; the open one either way. */
    suspend fun insertOrOpen(report: Report): Report

    suspend fun countSince(reporterId: UserId, since: Instant): Pair<Int, Instant?>

    /** The space's post reports, in [open] or done state. */
    suspend fun ofSpace(spaceId: SpaceId, open: Boolean): List<Report>

    /** Closes every open report on [postId]; the reports it closed. */
    suspend fun resolve(
        spaceId: SpaceId,
        postId: PostId,
        by: UserId,
        action: ReportAction,
        at: Instant,
    ): List<Report>

    /** Circaso's queue, newest first. */
    suspend fun all(offset: Int, limit: Int): List<Report>
}
