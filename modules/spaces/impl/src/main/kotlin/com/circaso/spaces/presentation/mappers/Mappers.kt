package com.circaso.spaces.presentation.mappers

import com.circaso.shared.Asset
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import com.circaso.spaces.api.PostId
import com.circaso.spaces.api.SpaceId
import com.circaso.spaces.domain.errors.SpacesError
import com.circaso.spaces.domain.models.CreatePostCommand
import com.circaso.spaces.domain.models.CreateSpaceCommand
import com.circaso.spaces.domain.models.HandleCheck
import com.circaso.spaces.domain.models.InviteResult
import com.circaso.spaces.domain.models.InvitesView
import com.circaso.spaces.domain.models.Known
import com.circaso.spaces.domain.models.MeView
import com.circaso.spaces.domain.models.MediaKind
import com.circaso.spaces.domain.models.MediaPurpose
import com.circaso.spaces.domain.models.MediaUpload
import com.circaso.spaces.domain.models.MemberView
import com.circaso.spaces.domain.models.MembersView
import com.circaso.spaces.domain.models.MySpaceView
import com.circaso.spaces.domain.models.OpsReportView
import com.circaso.spaces.domain.models.Page
import com.circaso.spaces.domain.models.PaymentView
import com.circaso.spaces.domain.models.Period
import com.circaso.spaces.domain.models.PostView
import com.circaso.spaces.domain.models.PostingMode
import com.circaso.spaces.domain.models.PrepareMediaCommand
import com.circaso.spaces.domain.models.Price
import com.circaso.spaces.domain.models.PrivateMessages
import com.circaso.spaces.domain.models.ReactionResult
import com.circaso.spaces.domain.models.ReceivedInviteView
import com.circaso.spaces.domain.models.ReportCommand
import com.circaso.spaces.domain.models.ReportGroupView
import com.circaso.spaces.domain.models.ReportReason
import com.circaso.spaces.domain.models.Role
import com.circaso.spaces.domain.models.SearchView
import com.circaso.spaces.domain.models.SpaceSummaryView
import com.circaso.spaces.domain.models.SpaceView
import com.circaso.spaces.domain.models.SuggestionView
import com.circaso.spaces.domain.models.TeamEntry
import com.circaso.spaces.domain.models.Topic
import com.circaso.spaces.domain.models.UpdateSpaceCommand
import com.circaso.spaces.domain.models.Visibility
import com.circaso.spaces.domain.support.toFrame
import com.circaso.spaces.domain.support.usdcAmount
import com.circaso.spaces.presentation.dto.CreatePostRequest
import com.circaso.spaces.presentation.dto.CreateSpaceRequest
import com.circaso.spaces.presentation.dto.HandleAvailabilityResponse
import com.circaso.spaces.presentation.dto.InvitePeopleResponse
import com.circaso.spaces.presentation.dto.InviteResultDto
import com.circaso.spaces.presentation.dto.InvitesResponse
import com.circaso.spaces.presentation.dto.JoinRequestDto
import com.circaso.spaces.presentation.dto.KnownDto
import com.circaso.spaces.presentation.dto.LastPostDto
import com.circaso.spaces.presentation.dto.MeDto
import com.circaso.spaces.presentation.dto.MediaUploadResponse
import com.circaso.spaces.presentation.dto.MemberDto
import com.circaso.spaces.presentation.dto.MembersResponse
import com.circaso.spaces.presentation.dto.MembershipDto
import com.circaso.spaces.presentation.dto.MembershipPaymentResponse
import com.circaso.spaces.presentation.dto.MoneyDto
import com.circaso.spaces.presentation.dto.MyInviteDto
import com.circaso.spaces.presentation.dto.MyInvitesResponse
import com.circaso.spaces.presentation.dto.MySpaceDto
import com.circaso.spaces.presentation.dto.MySpacesResponse
import com.circaso.spaces.presentation.dto.OpsReportDto
import com.circaso.spaces.presentation.dto.OpsReportsResponse
import com.circaso.spaces.presentation.dto.PersonDto
import com.circaso.spaces.presentation.dto.PostsResponse
import com.circaso.spaces.presentation.dto.PrepareMediaRequest
import com.circaso.spaces.presentation.dto.PriceDto
import com.circaso.spaces.presentation.dto.PriceRequest
import com.circaso.spaces.presentation.dto.ReactionDto
import com.circaso.spaces.presentation.dto.ReactionResponse
import com.circaso.spaces.presentation.dto.ReceivedInviteDto
import com.circaso.spaces.presentation.dto.ReportGroupDto
import com.circaso.spaces.presentation.dto.ReportRequest
import com.circaso.spaces.presentation.dto.ReportsResponse
import com.circaso.spaces.presentation.dto.RequestStateDto
import com.circaso.spaces.presentation.dto.ResolutionDto
import com.circaso.spaces.presentation.dto.SearchResponse
import com.circaso.spaces.presentation.dto.SentInviteDto
import com.circaso.spaces.presentation.dto.SpaceDto
import com.circaso.spaces.presentation.dto.SpaceSummaryDto
import com.circaso.spaces.presentation.dto.SpaceWalletDto
import com.circaso.spaces.presentation.dto.SuggestionDto
import com.circaso.spaces.presentation.dto.SuggestionsResponse
import com.circaso.spaces.presentation.dto.UpdateSpaceRequest
import com.circaso.spaces.presentation.dto.UploadHeadersDto
import com.circaso.users.api.PublicProfile
import java.math.BigDecimal
import java.util.UUID

// ---- request → command: shapes only; business rules are the use cases' ----

private fun malformed(field: String, problem: String = "is not valid") =
    Resource.error(SharedError.Malformed(field, problem))

private inline fun <reified E : Enum<E>> enumOf(raw: String?): E? =
    raw?.let { v ->
        enumValues<E>().firstOrNull {
            it.name ==
                v.trim().uppercase()
        }
    }

fun parseSpaceId(raw: String?): Resource<SpaceId> =
    raw?.let(SpaceId::parse)?.let { Resource.success(it) } ?: malformed("spaceId")

fun parsePostId(raw: String?, field: String = "postId"): Resource<PostId> =
    raw?.let(PostId::parse)?.let { Resource.success(it) } ?: malformed(field)

fun parseUserId(raw: String?, field: String = "userId"): Resource<UserId> =
    raw?.let(UserId::parse)?.let { Resource.success(it) } ?: malformed(field)

fun parseUuid(raw: String?, field: String): Resource<UUID> =
    raw?.let(Uuid7::parse)?.let { Resource.success(it) } ?: malformed(field)

/** `?cursor=` (an offset this server gave) and `?limit=` 1 to [max]. */
fun parsePage(
    cursor: String?,
    limit: String?,
    default: Int,
    max: Int,
): Resource<Pair<Int, Int>> {
    val offset = cursor?.let { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return malformed("cursor") } ?: 0
    val size = limit?.let { it.toIntOrNull() ?: return malformed("limit") } ?: default
    if (size !in 1..max) return malformed("limit", "1 to $max")
    return Resource.success(offset to size)
}

fun parseLimit(
    limit: String?,
    default: Int,
    max: Int,
): Resource<Int> {
    val size = limit?.let { it.toIntOrNull() ?: return malformed("limit") } ?: default
    if (size !in 1..max) return malformed("limit", "1 to $max")
    return Resource.success(size)
}

fun parseTopic(raw: String?): Resource<Topic?> {
    if (raw.isNullOrBlank()) return Resource.success(null)
    return enumOf<Topic>(raw)?.let { Resource.success(it) } ?: malformed("topic")
}

fun <E : Enum<E>> parseEnum(
    raw: String?,
    field: String,
    values: Array<E>,
): Resource<E> =
    values.firstOrNull { it.name == raw?.trim()?.uppercase() }?.let { Resource.success(it) } ?: malformed(field)

/** USDC only; `amountMinor` or `amount`, and both only if they agree. */
fun PriceRequest.toPrice(): Resource<Price> {
    if (asset.uppercase() != Asset.USDC.symbol) return Resource.error(SpacesError.PriceInvalid)
    val period = enumOf<Period>(period) ?: return malformed("price.period")
    val fromMinor = amountMinor?.let { it.trim().toLongOrNull() ?: return malformed("price.amountMinor") }
    val fromWhole =
        amount?.let {
            runCatching { BigDecimal(it.trim()).movePointRight(Asset.USDC.decimals).longValueExact() }.getOrNull()
                ?: return malformed("price.amount")
        }
    val minor = fromMinor ?: fromWhole ?: return malformed("price", "needs amountMinor or amount")
    if (fromMinor != null && fromWhole != null &&
        fromMinor != fromWhole
    ) {
        return malformed("price", "amountMinor and amount differ")
    }
    return Resource.success(Price(minor, period))
}

fun CreateSpaceRequest.toCommand(
    ownerId: UserId,
    country: String?,
    createKey: String,
): Resource<CreateSpaceCommand> {
    val topic = enumOf<Topic>(topic) ?: return malformed("topic")
    val visibility = enumOf<Visibility>(visibility) ?: return malformed("visibility")
    val posting = enumOf<PostingMode>(postingMode) ?: return malformed("postingMode")
    val messages = enumOf<PrivateMessages>(privateMessages) ?: return malformed("privateMessages")
    val photo = photoMediaId?.let { Uuid7.parse(it) ?: return malformed("photoMediaId") }
    val cover = coverMediaId?.let { Uuid7.parse(it) ?: return malformed("coverMediaId") }
    val parsedPrice =
        price?.let {
            when (val read = it.toPrice()) {
                is Resource.Success -> read.data
                is Resource.Error -> return read
            }
        }
    val entries =
        team.map { entry ->
            val id = UserId.parse(entry.userId) ?: return malformed("team.userId")
            val role = enumOf<Role>(entry.role) ?: return Resource.error(SpacesError.TeamInvalid)
            TeamEntry(id, role)
        }
    if (createKey.isBlank()) return malformed("Idempotency-Key", "is required")
    return Resource.success(
        CreateSpaceCommand(
            ownerId = ownerId,
            name = name,
            handle = handle,
            about = about,
            topic = topic,
            photoMediaId = photo,
            coverMediaId = cover,
            visibility = visibility,
            price = parsedPrice,
            postingMode = posting,
            privateMessages = messages,
            team = entries,
            country = country?.trim()?.uppercase()?.takeIf { it.length == 2 },
            createKey = createKey,
        ),
    )
}

/** SP5: fields left out (null) stay; `removePhoto` / `removeCover` take the photo or cover away. */
fun UpdateSpaceRequest.toCommand(caller: UserId, spaceId: SpaceId): Resource<UpdateSpaceCommand> {
    val photo = photoMediaId?.let { Uuid7.parse(it) ?: return malformed("photoMediaId") }
    val cover = coverMediaId?.let { Uuid7.parse(it) ?: return malformed("coverMediaId") }
    val parsedPrice =
        price?.let {
            when (val read = it.toPrice()) {
                is Resource.Success -> read.data
                is Resource.Error -> return read
            }
        }
    return Resource.success(
        UpdateSpaceCommand(
            caller = caller,
            spaceId = spaceId,
            name = name,
            handle = handle,
            about = about,
            topic = topic?.let { enumOf<Topic>(it) ?: return malformed("topic") },
            photoMediaId = photo,
            removePhoto = removePhoto,
            coverMediaId = cover,
            removeCover = removeCover,
            visibility = visibility?.let { enumOf<Visibility>(it) ?: return malformed("visibility") },
            price = parsedPrice,
            postingMode = postingMode?.let { enumOf<PostingMode>(it) ?: return malformed("postingMode") },
            privateMessages =
                privateMessages?.let { enumOf<PrivateMessages>(it) ?: return malformed("privateMessages") },
        ),
    )
}

fun CreatePostRequest.toCommand(caller: UserId, spaceId: SpaceId): Resource<CreatePostCommand> {
    val client = runCatching { UUID.fromString(clientPostId.trim()) }.getOrNull() ?: return malformed("clientPostId")
    val reply = replyToPostId?.let { PostId.parse(it) ?: return malformed("replyToPostId") }
    val files = mediaIds.map { Uuid7.parse(it) ?: return malformed("mediaIds") }
    return Resource.success(CreatePostCommand(caller, spaceId, client, text, reply, files))
}

fun PrepareMediaRequest.toCommand(owner: UserId): Resource<PrepareMediaCommand> {
    val purpose = enumOf<MediaPurpose>(purpose) ?: return Resource.error(SpacesError.MediaInvalid)
    val kind = enumOf<MediaKind>(kind) ?: return Resource.error(SpacesError.MediaInvalid)
    return Resource.success(
        PrepareMediaCommand(owner, purpose, kind, contentType.trim().lowercase(), sizeBytes, width, height, durationMs),
    )
}

fun ReportRequest.toCommand(reporter: UserId, spaceId: SpaceId): Resource<ReportCommand> {
    val post = postId?.let { PostId.parse(it) ?: return malformed("postId") }
    val why = enumOf<ReportReason>(reason) ?: return Resource.error(SpacesError.ReportInvalid)
    return Resource.success(ReportCommand(reporter, spaceId, post, why, detail))
}

// ---- model → response ----

fun PublicProfile.toDto() = PersonDto(userId.toString(), displayName, handle, avatarUrl)

fun Price.toDto() = PriceDto(Asset.USDC.symbol, amountMinor.toString(), usdcAmount(amountMinor), period.name)

fun money(minor: Long) = MoneyDto(Asset.USDC.symbol, minor.toString(), usdcAmount(minor))

fun Known.toDto() = KnownDto(names, count)

fun SpaceSummaryView.toDto(): SpaceSummaryDto =
    SpaceSummaryDto(
        spaceId = space.spaceId.toString(),
        handle = space.handle,
        name = space.name,
        about = space.about.takeIf { showAbout },
        topic = space.topic.name,
        photoUrl = photoUrl,
        coverUrl = coverUrl,
        visibility = space.visibility.name,
        price = space.price?.toDto(),
        memberCount = space.memberCount,
        state = space.state.name,
    )

fun MeView.toDto(): MeDto =
    MeDto(
        relation = relation.name,
        role = role?.name,
        joinedAt = joinedAt?.toString(),
        invitedBy = invitedBy?.toDto(),
        membership =
            membership?.let {
                MembershipDto(
                    it.state.name,
                    it.period.name,
                    it.paidUntil?.toString(),
                    it.graceUntil?.toString(),
                    it.renewFrom?.toString(),
                    it.complimentary,
                    it.nextPrice?.toDto(),
                )
            },
        mutedUntil = mutedUntil?.toString(),
        notifications = notifications.name,
        request = request?.let { RequestStateDto(it.state.name, it.at.toString(), it.canAskAgainAt?.toString()) },
        invite =
            invite?.let {
                MyInviteDto(
                    it.inviteId.toString(),
                    it.invitedBy.toDto(),
                    it.invitedAt.toString(),
                    it.expiresAt.toString(),
                )
            },
        unreadCount = unreadCount,
        lastReadPostId = lastReadPostId?.toString(),
    )

fun SpaceView.toDto(): SpaceDto {
    val s = summary.toDto()
    val space = summary.space
    return SpaceDto(
        spaceId = s.spaceId,
        handle = s.handle,
        name = s.name,
        about = s.about,
        topic = s.topic,
        photoUrl = s.photoUrl,
        coverUrl = s.coverUrl,
        visibility = s.visibility,
        price = s.price,
        memberCount = s.memberCount,
        state = s.state,
        owner = owner.toDto(),
        postingMode = space.postingMode.name,
        privateMessages = space.effectivePrivateMessages.name,
        createdAt = space.createdAt.toString(),
        version = space.version,
        known = known.toDto(),
        wallet = wallet?.let { SpaceWalletDto(it.walletId?.toString(), it.status, it.canSign) },
        me = me.toDto(),
    )
}

fun HandleCheck.toResponse() = HandleAvailabilityResponse(handle, available, suggestions)

fun Page<MySpaceView>.toResponse() =
    MySpacesResponse(
        items.map {
            MySpaceDto(
                it.summary.toDto(),
                it.me.toDto(),
                it.lastPost?.let { p ->
                    LastPostDto(p.postId.toString(), p.authorName, p.text, p.kind, p.at.toString(), p.removed)
                },
                it.unreadCount,
                it.activityAt.toString(),
            )
        },
        next,
    )

fun Page<SuggestionView>.toSuggestions() =
    SuggestionsResponse(
        items.map {
            val s = it.summary.toDto()
            SuggestionDto(
                s.spaceId,
                s.handle,
                s.name,
                s.about,
                s.topic,
                s.photoUrl,
                s.coverUrl,
                s.visibility,
                s.price,
                s.memberCount,
                s.state,
                it.reason.name,
                it.known.toDto(),
            )
        },
        next,
    )

fun SearchView.toResponse() = SearchResponse(exact?.toDto(), spaces.map { it.toDto() }, next)

fun PaymentView.toResponse() =
    MembershipPaymentResponse(
        paymentId = payment.paymentId.toString(),
        spaceId = payment.spaceId.toString(),
        kind = payment.kind.name,
        amount = money(payment.amountMinor),
        period = payment.period.name,
        periodStart = periodStart.toString(),
        periodEnd = periodEnd?.toString(),
        previousAmount = payment.previousAmountMinor?.let(::money),
        walletId = walletId.toString(),
        blockchains = blockchains,
        expiresAt = payment.expiresAt.toString(),
    )

fun InvitesView.toResponse() =
    InvitesResponse(
        requests.map { JoinRequestDto(it.user.toDto(), it.requestedAt.toString()) },
        invites.map {
            SentInviteDto(
                it.invite.inviteId.toString(),
                it.user.toDto(),
                it.invitedBy.toDto(),
                it.state.name,
                it.invite.invitedAt.toString(),
                it.invite.expiresAt.toString(),
                it.joinedAt?.toString(),
            )
        },
    )

fun List<InviteResult>.toResponse() =
    InvitePeopleResponse(
        map {
            InviteResultDto(it.userId.toString(), it.outcome.name)
        },
    )

fun List<ReceivedInviteView>.toResponse() =
    MyInvitesResponse(
        map {
            ReceivedInviteDto(
                it.invite.inviteId.toString(),
                it.space.toDto(),
                it.invitedBy.toDto(),
                it.invite.invitedAt.toString(),
                it.invite.expiresAt.toString(),
                it.known.toDto(),
            )
        },
    )

fun MemberView.toDto() =
    MemberDto(
        profile.userId.toString(),
        profile.displayName,
        profile.handle,
        profile.avatarUrl,
        role?.name,
        joinedAt?.toString(),
        mutedUntil?.toString(),
        bannedAt?.toString(),
    )

fun MembersView.toResponse() =
    MembersResponse(
        team.map {
            it.toDto()
        },
        contacts.map { it.toDto() },
        members.map { it.toDto() },
        count,
        next,
    )

fun Page<PostView>.toResponse() = PostsResponse(items.map { it.toFrame() }, next)

fun ReactionResult.toResponse() =
    ReactionResponse(
        postId.toString(),
        reactions.map {
            ReactionDto(it.emoji, it.count)
        },
        myReaction,
    )

fun MediaUpload.toResponse() =
    MediaUploadResponse(mediaId.toString(), uploadUrl, UploadHeadersDto(contentType), expiresAt.toString())

fun List<ReportGroupView>.toResponse() =
    ReportsResponse(
        map {
            ReportGroupDto(
                post = it.post.toFrame(),
                reason = it.reason.name,
                count = it.count,
                firstAt = it.firstAt.toString(),
                lastAt = it.lastAt.toString(),
                state = it.state.name,
                resolution =
                    if (it.action != null && it.resolvedBy != null && it.resolvedAt != null) {
                        ResolutionDto(it.action.name, it.resolvedBy.toDto(), it.resolvedAt.toString())
                    } else {
                        null
                    },
            )
        },
    )

fun Page<OpsReportView>.toResponse() =
    OpsReportsResponse(
        items.map {
            OpsReportDto(
                reportId = it.report.reportId.toString(),
                spaceId = it.report.spaceId.toString(),
                spaceName = it.spaceName,
                postId = it.report.postId?.toString(),
                postText = it.postText,
                reporterId = it.report.reporterId.toString(),
                reason = it.report.reason.name,
                detail = it.report.detail,
                state = it.report.state.name,
                createdAt = it.report.createdAt.toString(),
                action = it.report.action?.name,
            )
        },
        next,
    )
