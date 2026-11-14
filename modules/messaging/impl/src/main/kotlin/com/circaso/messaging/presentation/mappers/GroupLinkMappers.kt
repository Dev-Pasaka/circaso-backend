package com.circaso.messaging.presentation.mappers

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.GroupLinkToken
import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.GroupLink
import com.circaso.messaging.domain.models.LinkLookup
import com.circaso.messaging.domain.models.LinkPreview
import com.circaso.messaging.domain.models.PublishLinkCommand
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.messaging.presentation.dto.GroupLinkDto
import com.circaso.messaging.presentation.dto.GroupLinkPreviewResponse
import com.circaso.messaging.presentation.dto.GroupLinkResponse
import com.circaso.messaging.presentation.dto.PublishGroupLinkRequest
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.util.Base64

/** A token of the wrong shape can't be a link: it answers like one that no longer works, never `malformed`. */
fun parseLinkToken(raw: String?): Resource<GroupLinkToken> =
    GroupLinkToken.parse(raw)?.let { Resource.success(it) } ?: Resource.error(MessagingError.LinkNotFound)

/** The name and description trimmed (a blank description is none); a photo that isn't base64 is `link_invalid`. */
fun PublishGroupLinkRequest.toCommand(caller: UserId, chatId: ChatId): Resource<PublishLinkCommand> {
    val bytes =
        photo?.let {
            runCatching { Base64.getDecoder().decode(it) }.getOrNull()?.takeIf { decoded -> decoded.isNotEmpty() }
                ?: return Resource.error(MessagingError.LinkInvalid)
        }
    val preview = LinkPreview(name.trim(), description?.trim()?.takeIf { it.isNotEmpty() }, bytes)
    return Resource.success(PublishLinkCommand(caller, chatId, preview, reset))
}

fun GroupLink?.toResponse(config: MessagingConfig) = GroupLinkResponse(this?.toDto(config))

fun GroupLink.toDto(config: MessagingConfig): GroupLinkDto =
    GroupLinkDto(
        token = token.value,
        url = config.linkUrl(token.value),
        name = preview?.name.orEmpty(),
        description = preview?.description,
        photoUrl = photoUrl(config),
        createdBy = createdBy.toString(),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

fun LinkLookup.toResponse(config: MessagingConfig) =
    GroupLinkPreviewResponse(
        name = link.preview?.name.orEmpty(),
        description = link.preview?.description,
        photoUrl = link.photoUrl(config),
        memberCount = memberCount,
        member = chatId != null,
        chatId = chatId?.toString(),
    )

private fun GroupLink.photoUrl(config: MessagingConfig): String? =
    preview?.photo?.let { config.linkPhotoUrl(token.value, updatedAt.toEpochMilli()) }
