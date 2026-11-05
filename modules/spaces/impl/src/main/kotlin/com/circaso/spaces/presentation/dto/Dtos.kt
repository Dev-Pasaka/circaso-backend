package com.circaso.spaces.presentation.dto

import com.circaso.spaces.api.FramePost
import kotlinx.serialization.Serializable

// ---- requests ----

@Serializable
data class PriceRequest(val asset: String = "USDC", val amountMinor: String? = null, val amount: String? = null, val period: String)

@Serializable
data class TeamEntryRequest(val userId: String, val role: String)

/** SP2 `POST /spaces`. */
@Serializable
data class CreateSpaceRequest(
    val name: String,
    val handle: String,
    val about: String = "",
    val topic: String,
    val photoMediaId: String? = null,
    val coverMediaId: String? = null,
    val visibility: String,
    val price: PriceRequest? = null,
    val postingMode: String = "EVERYONE",
    val privateMessages: String = "ANYONE",
    val team: List<TeamEntryRequest> = emptyList(),
)

/**
 * SP5's body as the docs show it: any of these fields. It is read as JSON in the controller, so `photoMediaId` and
 * `coverMediaId` sent as null remove them, while fields left out stay.
 */
@Serializable
data class UpdateSpaceRequest(
    val name: String? = null,
    val handle: String? = null,
    val about: String? = null,
    val topic: String? = null,
    val photoMediaId: String? = null,
    val coverMediaId: String? = null,
    val visibility: String? = null,
    val price: PriceRequest? = null,
    val postingMode: String? = null,
    val privateMessages: String? = null,
)

@Serializable
data class NotificationsRequest(val level: String)

@Serializable
data class InvitePeopleRequest(val userIds: List<String>)

@Serializable
data class RoleRequest(val role: String)

@Serializable
data class MuteRequest(val duration: String)

@Serializable
data class CreatePostRequest(
    val clientPostId: String,
    val text: String? = null,
    val replyToPostId: String? = null,
    val mediaIds: List<String> = emptyList(),
)

/** SP31: `emoji` null clears the reaction. */
@Serializable
data class ReactionRequest(val emoji: String? = null)

@Serializable
data class ReadRequest(val postId: String)

@Serializable
data class PrepareMediaRequest(
    val purpose: String,
    val kind: String,
    val contentType: String,
    val sizeBytes: Long,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
)

@Serializable
data class ReportRequest(val postId: String? = null, val reason: String, val detail: String? = null)

@Serializable
data class ResolveReportRequest(val action: String, val muteDuration: String? = null)

@Serializable
data class RemoveSpaceRequest(val reason: String)

// ---- responses ----

@Serializable
data class MoneyDto(val asset: String, val amountMinor: String, val amount: String)

@Serializable
data class PriceDto(val asset: String, val amountMinor: String, val amount: String, val period: String)

@Serializable
data class PersonDto(val userId: String, val displayName: String, val handle: String? = null, val avatarUrl: String? = null)

@Serializable
data class KnownDto(val names: List<String>, val count: Int)

@Serializable
data class SpaceSummaryDto(
    val spaceId: String,
    val handle: String,
    val name: String,
    val about: String? = null,
    val topic: String,
    val photoUrl: String? = null,
    val coverUrl: String? = null,
    val visibility: String,
    val price: PriceDto? = null,
    val memberCount: Int,
    val state: String,
)

@Serializable
data class SpaceWalletDto(val walletId: String? = null, val status: String, val canSign: Boolean)

@Serializable
data class MembershipDto(
    val state: String,
    val period: String,
    val paidUntil: String? = null,
    val graceUntil: String? = null,
    val renewFrom: String? = null,
    val complimentary: Boolean,
    val nextPrice: PriceDto? = null,
)

@Serializable
data class RequestStateDto(val state: String, val at: String, val canAskAgainAt: String? = null)

@Serializable
data class MyInviteDto(val inviteId: String, val invitedBy: PersonDto, val invitedAt: String, val expiresAt: String)

@Serializable
data class MeDto(
    val relation: String,
    val role: String? = null,
    val joinedAt: String? = null,
    val invitedBy: PersonDto? = null,
    val membership: MembershipDto? = null,
    val mutedUntil: String? = null,
    val notifications: String,
    val request: RequestStateDto? = null,
    val invite: MyInviteDto? = null,
    val unreadCount: Int,
    val lastReadPostId: String? = null,
)

/** SP3's `Space`: the summary's fields and the page's own. */
@Serializable
data class SpaceDto(
    val spaceId: String,
    val handle: String,
    val name: String,
    val about: String? = null,
    val topic: String,
    val photoUrl: String? = null,
    val coverUrl: String? = null,
    val visibility: String,
    val price: PriceDto? = null,
    val memberCount: Int,
    val state: String,
    val owner: PersonDto,
    val postingMode: String,
    val privateMessages: String,
    val createdAt: String,
    val version: Long,
    val known: KnownDto,
    val wallet: SpaceWalletDto? = null,
    val me: MeDto,
)

@Serializable
data class HandleAvailabilityResponse(val handle: String, val available: Boolean, val suggestions: List<String>)

@Serializable
data class UpdateSpaceResponse(val space: SpaceDto, val notified: Int)

@Serializable
data class DeletedResponse(val deleted: Boolean)

@Serializable
data class LastPostDto(
    val postId: String,
    val authorName: String,
    val text: String? = null,
    val kind: String,
    val at: String,
    val removed: Boolean,
)

@Serializable
data class MySpaceDto(
    val space: SpaceSummaryDto,
    val me: MeDto,
    val lastPost: LastPostDto? = null,
    val unreadCount: Int,
    val activityAt: String,
)

@Serializable
data class MySpacesResponse(val spaces: List<MySpaceDto>, val next: String? = null)

@Serializable
data class SuggestionDto(
    val spaceId: String,
    val handle: String,
    val name: String,
    val about: String? = null,
    val topic: String,
    val photoUrl: String? = null,
    val coverUrl: String? = null,
    val visibility: String,
    val price: PriceDto? = null,
    val memberCount: Int,
    val state: String,
    val reason: String,
    val known: KnownDto,
)

@Serializable
data class SuggestionsResponse(val spaces: List<SuggestionDto>, val next: String? = null)

@Serializable
data class SearchResponse(val exact: SpaceSummaryDto? = null, val spaces: List<SpaceSummaryDto>, val next: String? = null)

@Serializable
data class LeftResponse(val left: Boolean)

@Serializable
data class MembershipPaymentResponse(
    val paymentId: String,
    val spaceId: String,
    val kind: String,
    val amount: MoneyDto,
    val period: String,
    val periodStart: String,
    val periodEnd: String? = null,
    val previousAmount: MoneyDto? = null,
    val walletId: String,
    val blockchains: List<String>,
    val expiresAt: String,
)

@Serializable
data class JoinRequestDto(val user: PersonDto, val requestedAt: String)

@Serializable
data class SentInviteDto(
    val inviteId: String,
    val user: PersonDto,
    val invitedBy: PersonDto,
    val state: String,
    val invitedAt: String,
    val expiresAt: String,
    val joinedAt: String? = null,
)

@Serializable
data class InvitesResponse(val requests: List<JoinRequestDto>, val invites: List<SentInviteDto>)

@Serializable
data class RequestAnswerResponse(val state: String)

@Serializable
data class InviteResultDto(val userId: String, val outcome: String)

@Serializable
data class InvitePeopleResponse(val results: List<InviteResultDto>)

@Serializable
data class CancelledResponse(val cancelled: Boolean)

@Serializable
data class ReceivedInviteDto(
    val inviteId: String,
    val space: SpaceSummaryDto,
    val invitedBy: PersonDto,
    val invitedAt: String,
    val expiresAt: String,
    val known: KnownDto,
)

@Serializable
data class MyInvitesResponse(val invites: List<ReceivedInviteDto>)

@Serializable
data class DeclinedResponse(val declined: Boolean)

@Serializable
data class MemberDto(
    val userId: String,
    val displayName: String,
    val handle: String? = null,
    val avatarUrl: String? = null,
    val role: String? = null,
    val joinedAt: String? = null,
    val mutedUntil: String? = null,
    val bannedAt: String? = null,
)

@Serializable
data class MembersResponse(
    val team: List<MemberDto>,
    val contacts: List<MemberDto>,
    val members: List<MemberDto>,
    val count: Int,
    val next: String? = null,
)

@Serializable
data class RemovedResponse(val removed: Boolean)

@Serializable
data class BannedResponse(val banned: Boolean)

@Serializable
data class PostsResponse(val posts: List<FramePost>, val next: String? = null)

@Serializable
data class ReactionDto(val emoji: String, val count: Int)

@Serializable
data class ReactionResponse(val postId: String, val reactions: List<ReactionDto>, val myReaction: String? = null)

@Serializable
data class UnreadResponse(val unreadCount: Int)

@Serializable
data class UploadHeadersDto(
    @kotlinx.serialization.SerialName("Content-Type") val contentType: String,
)

@Serializable
data class MediaUploadResponse(val mediaId: String, val uploadUrl: String, val uploadHeaders: UploadHeadersDto, val expiresAt: String)

@Serializable
data class MediaCompletedResponse(val mediaId: String, val sizeBytes: Long)

@Serializable
data class ReportResponse(val reportId: String)

@Serializable
data class ResolvedResponse(val resolved: Boolean)

@Serializable
data class ResolutionDto(val action: String, val by: PersonDto, val at: String)

@Serializable
data class ReportGroupDto(
    val post: FramePost,
    val reason: String,
    val count: Int,
    val firstAt: String,
    val lastAt: String,
    val state: String,
    val resolution: ResolutionDto? = null,
)

@Serializable
data class ReportsResponse(val reports: List<ReportGroupDto>, val next: String? = null)

@Serializable
data class OpsReportDto(
    val reportId: String,
    val spaceId: String,
    val spaceName: String,
    val postId: String? = null,
    val postText: String? = null,
    val reporterId: String,
    val reason: String,
    val detail: String? = null,
    val state: String,
    val createdAt: String,
    val action: String? = null,
)

@Serializable
data class OpsReportsResponse(val reports: List<OpsReportDto>, val next: String? = null)
