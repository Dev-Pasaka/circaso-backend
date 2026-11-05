package com.circaso.spaces.api

import com.circaso.shared.UserId
import kotlinx.serialization.Serializable

/**
 * The `data` of each realtime frame `spaces` sends (Spaces API → Realtime), one class per type. Every frame is kept
 * 7 days for replay. They live in the contract so the gateway reads them typed when it words a push; spaces isn't
 * end-to-end encrypted, so a push may name the space and the people in it.
 */
object SpacesFrames {
    const val MODULE = "spaces"
    const val POST_NEW = "space.post_new"
    const val POST_UPDATED = "space.post_updated"
    const val UPDATED = "space.updated"
    const val MEMBERSHIP_UPDATED = "space.membership_updated"
    const val PRICE_CHANGED = "space.price_changed"
    const val INVITED = "space.invited"
    const val INVITE_REMOVED = "space.invite_removed"
    const val INVITE_ACCEPTED = "space.invite_accepted"
    const val REQUEST_NEW = "space.request_new"
    const val REPORT_NEW = "space.report_new"
    const val REPORT_RESOLVED = "space.report_resolved"
}

/** A frame spaces sends. [type] names it in the envelope and is not part of its `data`. */
sealed interface SpaceFrame {
    val type: String
}

/** A person as a frame carries them. */
@Serializable
data class FramePerson(
    val userId: UserId,
    val displayName: String,
    val handle: String? = null,
    val avatarUrl: String? = null,
    val role: String? = null,
)

@Serializable
data class FrameMedia(
    val mediaId: String,
    val kind: String,
    val contentType: String,
    val sizeBytes: Long,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val url: String? = null,
)

@Serializable
data class FrameReplyTo(
    val postId: PostId,
    val authorName: String,
    val text: String? = null,
    val removed: Boolean,
)

@Serializable
data class FrameReaction(val emoji: String, val count: Int)

@Serializable
data class FrameRemoved(val by: String, val at: String)

/** A post exactly as SP28 answers it (`Post`). */
@Serializable
data class FramePost(
    val postId: PostId,
    val spaceId: SpaceId,
    val clientPostId: String? = null,
    val author: FramePerson,
    val text: String? = null,
    val media: List<FrameMedia> = emptyList(),
    val replyTo: FrameReplyTo? = null,
    val reactions: List<FrameReaction> = emptyList(),
    val myReaction: String? = null,
    val createdAt: String,
    val removed: FrameRemoved? = null,
)

/**
 * `space.post_new`, to every member. [notify] is true for the members this post is pushed to (notifications `ALL`, or
 * `MENTIONS` when it replies to them or comes from the space's team); the gateway pushes only those.
 */
@Serializable
data class PostNewFrame(
    val spaceId: SpaceId,
    val spaceName: String,
    val post: FramePost,
    val notify: Boolean = false,
) : SpaceFrame {
    override val type: String get() = SpacesFrames.POST_NEW
}

/** `space.post_updated`, to every member: a post's reactions changed, or it was removed. */
@Serializable
data class PostUpdatedFrame(
    val spaceId: SpaceId,
    val postId: PostId,
    val reactions: List<FrameReaction>,
    val removed: FrameRemoved? = null,
) : SpaceFrame {
    override val type: String get() = SpacesFrames.POST_UPDATED
}

/** `space.updated`: the space changed (details, posting mode, price, state); the phone reads SP3 again. */
@Serializable
data class SpaceUpdatedFrame(val spaceId: SpaceId, val version: Long, val state: String) : SpaceFrame {
    override val type: String get() = SpacesFrames.UPDATED
}

/**
 * `space.membership_updated`, to the person: [reason] says what happened (`JOINED`, `RENEWED`, `ROLE_CHANGED`,
 * `MUTED`, `UNMUTED`, `REMOVED`, `BANNED`, `UNBANNED`, `REQUEST_DECLINED`, `RENEWAL_DUE`, `GRACE_STARTED`, `ENDED`,
 * `LEFT`); [until] is the mute's, period's or grace's end when the reason has one, and [role] the new role.
 */
@Serializable
data class MembershipUpdatedFrame(
    val spaceId: SpaceId,
    val spaceName: String,
    val reason: String,
    val until: String? = null,
    val role: String? = null,
) : SpaceFrame {
    override val type: String get() = SpacesFrames.MEMBERSHIP_UPDATED
}

@Serializable
data class FramePrice(
    val asset: String,
    val amountMinor: String,
    val amount: String,
    val period: String,
)

/** `space.price_changed`, to every member: the new price applies to each from their next period. */
@Serializable
data class PriceChangedFrame(
    val spaceId: SpaceId,
    val spaceName: String,
    val price: FramePrice,
    val previous: FramePrice? = null,
) : SpaceFrame {
    override val type: String get() = SpacesFrames.PRICE_CHANGED
}

/** `space.invited`, to the invitee: the invite card at the top of Chats (SP20). Pushed. */
@Serializable
data class InvitedFrame(
    val inviteId: String,
    val spaceId: SpaceId,
    val spaceName: String,
    val inviterId: UserId,
    val inviterName: String,
) : SpaceFrame {
    override val type: String get() = SpacesFrames.INVITED
}

/** `space.invite_removed`, to the invitee: the card goes (cancelled, or the space went). */
@Serializable
data class InviteRemovedFrame(val inviteId: String, val spaceId: SpaceId) : SpaceFrame {
    override val type: String get() = SpacesFrames.INVITE_REMOVED
}

/** `space.invite_accepted`, to the inviter (SI3: "Pasaka joined"). Not pushed. */
@Serializable
data class InviteAcceptedFrame(
    val spaceId: SpaceId,
    val spaceName: String,
    val userId: UserId,
    val name: String,
) : SpaceFrame {
    override val type: String get() = SpacesFrames.INVITE_ACCEPTED
}

/** `space.request_new`, to the owner and admins of an invite-only space. Pushed. */
@Serializable
data class RequestNewFrame(
    val spaceId: SpaceId,
    val spaceName: String,
    val userId: UserId,
    val name: String,
) : SpaceFrame {
    override val type: String get() = SpacesFrames.REQUEST_NEW
}

/** `space.report_new`, to the space's moderators, admins and owner. Never names the reporter. */
@Serializable
data class ReportNewFrame(val spaceId: SpaceId, val postId: PostId, val count: Int) : SpaceFrame {
    override val type: String get() = SpacesFrames.REPORT_NEW
}

/** `space.report_resolved`, to each reporter. Pushed. */
@Serializable
data class ReportResolvedFrame(
    val spaceId: SpaceId,
    val spaceName: String,
    val reportId: String,
    val action: String,
) : SpaceFrame {
    override val type: String get() = SpacesFrames.REPORT_RESOLVED
}
