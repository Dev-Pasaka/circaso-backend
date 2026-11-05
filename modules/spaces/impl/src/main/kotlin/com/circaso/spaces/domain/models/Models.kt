package com.circaso.spaces.domain.models

import com.circaso.shared.UserId
import com.circaso.spaces.api.PostId
import com.circaso.spaces.api.SpaceId
import java.time.Instant
import java.util.UUID

/** One topic list everywhere: create, Discover chips, search (decision 2026-11-04). */
enum class Topic { BUSINESS, SHOPPING, FARMING, HEALTH, FAITH, SPORT, BOOKS, CHAMAS, MUSIC, OTHER }

/** Who can join (plan §5): follow for free, pay to join, or be invited (or let in after asking). */
enum class Visibility { PUBLIC, MEMBERS_ONLY, INVITE_ONLY }

/** How a members-only price is paid: once, or every month or year, each renewal signed by the member. */
enum class Period { ONE_TIME, MONTHLY, YEARLY }

/** A space is seen only by its owner and team until its wallet is active (plan §7); then live. */
enum class SpaceState { SETTING_UP, LIVE, DELETED, REMOVED }

enum class PostingMode { EVERYONE, ADMINS_ONLY }

enum class PrivateMessages { ANYONE, MEMBERS }

/** Each role adds to the one below it (SR2); [rank] orders them. */
enum class Role(val rank: Int) {
    MEMBER(0),
    MODERATOR(1),
    ADMIN(2),
    OWNER(3),
    ;

    fun atLeast(other: Role): Boolean = rank >= other.rank
}

enum class NotificationLevel { ALL, MENTIONS, OFF }

/** A paid membership: works while `ACTIVE` and for 3 days of `GRACE`, then `ENDED` until paid again. */
enum class PaidState { ACTIVE, GRACE, ENDED }

/** What the caller is to a space, as SP3's `me.relation` says it. */
enum class Relation { NONE, MEMBER, ENDED, REQUESTED, INVITED, BANNED }

/** USDC price of a members-only space, in minor units. */
data class Price(val amountMinor: Long, val period: Period)

data class Space(
    val spaceId: SpaceId,
    val handle: String,
    val name: String,
    val about: String,
    val topic: Topic,
    val photoMediaId: UUID?,
    val coverMediaId: UUID?,
    val visibility: Visibility,
    val price: Price?,
    val postingMode: PostingMode,
    val privateMessages: PrivateMessages,
    val state: SpaceState,
    val ownerId: UserId,
    val country: String?,
    val memberCount: Int,
    val lastPostAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
    val createKey: String?,
    val removedReason: String? = null,
) {
    val isLive: Boolean get() = state == SpaceState.LIVE
    val isGone: Boolean get() = state == SpaceState.DELETED || state == SpaceState.REMOVED

    /** An invite-only space's owner is reachable by members only (decisions, Phase 3). */
    val effectivePrivateMessages: PrivateMessages
        get() = if (visibility == Visibility.INVITE_ONLY) PrivateMessages.MEMBERS else privateMessages

    /** Shows in search and suggestions. */
    val isDiscoverable: Boolean get() = isLive && visibility != Visibility.INVITE_ONLY
}

/** One person in a space. A paid membership carries its [paidState]; an `ENDED` one keeps the row (Rejoin). */
data class Membership(
    val spaceId: SpaceId,
    val userId: UserId,
    val role: Role,
    val joinedAt: Instant,
    val invitedBy: UserId?,
    val notifications: NotificationLevel,
    val mutedUntil: Instant?,
    val paidState: PaidState?,
    val period: Period?,
    val paidUntil: Instant?,
    /** What the latest paid period cost, so a price change shows on the renewal card (SM3b). */
    val paidAmountMinor: Long?,
    val complimentary: Boolean,
    val remindedFor: Instant?,
    val readUpTo: UUID?,
) {
    /** In the space now: a free membership, or a paid one that hasn't ended. */
    val isActive: Boolean get() = paidState != PaidState.ENDED

    fun isMuted(now: Instant): Boolean = mutedUntil != null && now < mutedUntil
}

data class Ban(val spaceId: SpaceId, val userId: UserId, val bannedBy: UserId, val bannedAt: Instant)

enum class RequestState { PENDING, ACCEPTED, DECLINED, CANCELLED }

data class JoinRequest(
    val spaceId: SpaceId,
    val userId: UserId,
    val state: RequestState,
    val requestedAt: Instant,
    val answeredAt: Instant?,
)

enum class InviteState { INVITED, ACCEPTED, DECLINED, CANCELLED }

data class Invite(
    val inviteId: UUID,
    val spaceId: SpaceId,
    val inviteeId: UserId,
    val inviterId: UserId,
    val state: InviteState,
    val invitedAt: Instant,
    val expiresAt: Instant,
    val answeredAt: Instant?,
) {
    /** Live: can still be accepted or declined. */
    fun isLive(now: Instant): Boolean = state == InviteState.INVITED && now < expiresAt
}

enum class PaymentKind { JOIN, RENEWAL, REJOIN }

/** SP13: what one membership payment must be, held 15 minutes; confirmed by the wallet's `TransferConfirmed`. */
data class MembershipPayment(
    val paymentId: UUID,
    val spaceId: SpaceId,
    val payerId: UserId,
    val kind: PaymentKind,
    val amountMinor: Long,
    val period: Period,
    val previousAmountMinor: Long?,
    val createdAt: Instant,
    val expiresAt: Instant,
    val confirmedAt: Instant?,
)

enum class MediaPurpose { SPACE_PHOTO, SPACE_COVER, POST }

enum class MediaKind { IMAGE, VIDEO, VOICE }

enum class MediaState { PENDING, READY, USED }

/** One file for a space or a post, not encrypted (space content isn't end to end). */
data class SpaceMedia(
    val mediaId: UUID,
    val ownerId: UserId,
    val purpose: MediaPurpose,
    val kind: MediaKind,
    val contentType: String,
    val sizeBytes: Long,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val state: MediaState,
    val createdAt: Instant,
    val expiresAt: Instant,
)

enum class RemovedBy { AUTHOR, MODERATOR }

data class Post(
    val postId: PostId,
    val spaceId: SpaceId,
    val authorId: UserId,
    val clientPostId: UUID,
    val text: String?,
    val replyTo: PostId?,
    val mediaIds: List<UUID>,
    val createdAt: Instant,
    val removedAt: Instant?,
    val removedBy: RemovedBy?,
) {
    val isRemoved: Boolean get() = removedAt != null
}

data class ReactionCount(val emoji: String, val count: Int)

enum class ReportReason { SPAM_SCAM, COUNTERFEIT, HARASSMENT, HATE_VIOLENCE, SEXUAL, OTHER }

enum class ReportState { OPEN, DONE }

enum class ReportAction { KEEP, REMOVE_POST, MUTE, REMOVE_MEMBER }

data class Report(
    val reportId: UUID,
    val spaceId: SpaceId,
    val postId: PostId?,
    val reporterId: UserId,
    val reason: ReportReason,
    val detail: String?,
    val state: ReportState,
    val createdAt: Instant,
    val resolvedAt: Instant?,
    val resolvedBy: UserId?,
    val action: ReportAction?,
)

enum class MuteDuration { HOUR, DAY, WEEK, UNTIL_UNMUTED }
