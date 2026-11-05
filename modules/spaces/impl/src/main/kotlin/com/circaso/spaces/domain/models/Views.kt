package com.circaso.spaces.domain.models

import com.circaso.shared.UserId
import com.circaso.spaces.api.PostId
import com.circaso.spaces.api.SpaceId
import com.circaso.users.api.PublicProfile
import java.time.Instant
import java.util.UUID

/** What the caller sees of a space in a list (`SpaceSummary`): photo and cover URLs are pre-signed, 1 hour. */
data class SpaceSummaryView(
    val space: Space,
    val photoUrl: String?,
    val coverUrl: String?,
    /** False for an invite-only space seen from outside: its about stays hidden. */
    val showAbout: Boolean = true,
)

/** Contacts of the caller who are in the space: up to three names and how many in all. */
data class Known(val names: List<String>, val count: Int) {
    companion object {
        val NONE = Known(emptyList(), 0)
    }
}

data class WalletView(val walletId: UUID?, val status: String, val canSign: Boolean)

data class MembershipView(
    val state: PaidState,
    val period: Period,
    val paidUntil: Instant?,
    val graceUntil: Instant?,
    val renewFrom: Instant?,
    val complimentary: Boolean,
    val nextPrice: Price?,
)

data class RequestView(val state: RequestState, val at: Instant, val canAskAgainAt: Instant?)

data class InviteView(val inviteId: UUID, val invitedBy: PublicProfile, val invitedAt: Instant, val expiresAt: Instant)

/** SP3's `me`. */
data class MeView(
    val relation: Relation,
    val role: Role?,
    val joinedAt: Instant?,
    val invitedBy: PublicProfile?,
    val membership: MembershipView?,
    val mutedUntil: Instant?,
    val notifications: NotificationLevel,
    val request: RequestView?,
    val invite: InviteView?,
    val unreadCount: Int,
    val lastReadPostId: UUID?,
)

/** SP3's `Space`. */
data class SpaceView(
    val summary: SpaceSummaryView,
    val owner: PublicProfile,
    val known: Known,
    val wallet: WalletView?,
    val me: MeView,
)

data class LastPost(val postId: PostId, val authorName: String, val text: String?, val kind: String, val at: Instant, val removed: Boolean)

/** One row of SP7. */
data class MySpaceView(
    val summary: SpaceSummaryView,
    val me: MeView,
    val lastPost: LastPost?,
    val unreadCount: Int,
    val activityAt: Instant,
)

enum class SuggestionReason { CONTACTS, POPULAR_IN_COUNTRY, POPULAR }

data class SuggestionView(val summary: SpaceSummaryView, val reason: SuggestionReason, val known: Known)

data class Page<T>(val items: List<T>, val next: String?)

data class SearchView(val exact: SpaceSummaryView?, val spaces: List<SpaceSummaryView>, val next: String?)

data class HandleCheck(val handle: String, val available: Boolean, val suggestions: List<String>)

/** A person as a space shows them, with their role there (null when they aren't in it). */
data class MemberView(
    val profile: PublicProfile,
    val role: Role?,
    val joinedAt: Instant?,
    val mutedUntil: Instant?,
    val bannedAt: Instant?,
)

data class MembersView(
    val team: List<MemberView>,
    val contacts: List<MemberView>,
    val members: List<MemberView>,
    val count: Int,
    val next: String?,
)

data class PostMediaView(val media: SpaceMedia, val url: String?)

data class ReplyView(val postId: PostId, val authorName: String, val text: String?, val removed: Boolean)

data class PostView(
    val post: Post,
    val author: PublicProfile,
    val authorRole: Role?,
    val media: List<PostMediaView>,
    val replyTo: ReplyView?,
    val reactions: List<ReactionCount>,
    val myReaction: String?,
    /** Only the author's own phone gets its `clientPostId` back. */
    val mine: Boolean,
)

data class ReactionResult(val postId: PostId, val reactions: List<ReactionCount>, val myReaction: String?)

data class JoinRequestView(val user: PublicProfile, val requestedAt: Instant)

enum class SentInviteState { INVITED, JOINED, EXPIRED }

data class SentInviteView(
    val invite: Invite,
    val user: PublicProfile,
    val invitedBy: PublicProfile,
    val state: SentInviteState,
    val joinedAt: Instant?,
)

data class InvitesView(val requests: List<JoinRequestView>, val invites: List<SentInviteView>)

data class ReceivedInviteView(
    val invite: Invite,
    val space: SpaceSummaryView,
    val invitedBy: PublicProfile,
    val known: Known,
)

enum class InviteOutcome { INVITED, ALREADY_MEMBER, BANNED, UNREACHABLE }

data class InviteResult(val userId: UserId, val outcome: InviteOutcome)

data class PaymentView(
    val payment: MembershipPayment,
    val periodStart: Instant,
    val periodEnd: Instant?,
    val walletId: UUID,
    val blockchains: List<String>,
)

data class MediaUpload(val mediaId: UUID, val uploadUrl: String, val contentType: String, val expiresAt: Instant)

data class ReportGroupView(
    val post: PostView,
    val reason: ReportReason,
    val count: Int,
    val firstAt: Instant,
    val lastAt: Instant,
    val state: ReportState,
    val action: ReportAction?,
    val resolvedBy: PublicProfile?,
    val resolvedAt: Instant?,
)

data class OpsReportView(val report: Report, val spaceName: String, val postText: String?)

// ---- commands ----

data class TeamEntry(val userId: UserId, val role: Role)

data class CreateSpaceCommand(
    val ownerId: UserId,
    val name: String,
    val handle: String,
    val about: String,
    val topic: Topic,
    val photoMediaId: UUID?,
    val coverMediaId: UUID?,
    val visibility: Visibility,
    val price: Price?,
    val postingMode: PostingMode,
    val privateMessages: PrivateMessages,
    val team: List<TeamEntry>,
    val country: String?,
    val createKey: String,
)

/** SP5: null leaves a field as it is; [removePhoto] / [removeCover] clear them. */
data class UpdateSpaceCommand(
    val caller: UserId,
    val spaceId: SpaceId,
    val name: String? = null,
    val handle: String? = null,
    val about: String? = null,
    val topic: Topic? = null,
    val photoMediaId: UUID? = null,
    val removePhoto: Boolean = false,
    val coverMediaId: UUID? = null,
    val removeCover: Boolean = false,
    val visibility: Visibility? = null,
    val price: Price? = null,
    val postingMode: PostingMode? = null,
    val privateMessages: PrivateMessages? = null,
)

data class UpdatedSpace(val view: SpaceView, val notified: Int)

data class CreatePostCommand(
    val caller: UserId,
    val spaceId: SpaceId,
    val clientPostId: UUID,
    val text: String?,
    val replyTo: PostId?,
    val mediaIds: List<UUID>,
)

/** SP28's paging: [before] (newest first) or [after] (oldest first). */
data class PostsQuery(val caller: UserId, val spaceId: SpaceId, val before: PostId?, val after: PostId?, val limit: Int)

data class PrepareMediaCommand(
    val ownerId: UserId,
    val purpose: MediaPurpose,
    val kind: MediaKind,
    val contentType: String,
    val sizeBytes: Long,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
)

data class ReportCommand(
    val reporter: UserId,
    val spaceId: SpaceId,
    val postId: PostId?,
    val reason: ReportReason,
    val detail: String?,
)

data class MembersQuery(
    val caller: UserId,
    val spaceId: SpaceId,
    val filter: MemberFilter,
    val query: String?,
    val cursor: String?,
    val limit: Int,
)

enum class MemberFilter { ALL, MUTED, BANNED }
