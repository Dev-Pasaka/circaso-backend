package com.circaso.status.presentation.mappers

import com.circaso.shared.CircasoJson
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import com.circaso.status.api.MomentId
import com.circaso.status.domain.errors.StatusError
import com.circaso.status.domain.models.KeyCopy
import com.circaso.status.domain.models.MediaDownload
import com.circaso.status.domain.models.MediaKind
import com.circaso.status.domain.models.MediaUpload
import com.circaso.status.domain.models.MomentMedia
import com.circaso.status.domain.models.MomentPage
import com.circaso.status.domain.models.MomentView
import com.circaso.status.domain.models.PostMomentCommand
import com.circaso.status.domain.models.PostedMoment
import com.circaso.status.domain.models.PrepareMediaCommand
import com.circaso.status.domain.models.ViewRecord
import com.circaso.status.presentation.dto.KeyCopyDto
import com.circaso.status.presentation.dto.MediaCompletedResponse
import com.circaso.status.presentation.dto.MediaDownloadResponse
import com.circaso.status.presentation.dto.MediaUploadResponse
import com.circaso.status.presentation.dto.MomentDto
import com.circaso.status.presentation.dto.MomentsResponse
import com.circaso.status.presentation.dto.PostMomentRequest
import com.circaso.status.presentation.dto.PostedMomentResponse
import com.circaso.status.presentation.dto.PrepareMediaRequest
import com.circaso.status.presentation.dto.UploadHeadersDto
import com.circaso.status.presentation.dto.ViewDto
import com.circaso.status.presentation.dto.ViewMomentRequest
import com.circaso.status.presentation.dto.ViewsResponse
import kotlinx.serialization.SerializationException
import java.util.Base64
import java.util.UUID

// ---- request → command: shapes only; sizes, counts and who may do what are the use cases' ----

private fun malformed(field: String, why: String = "is not valid") = Resource.error(SharedError.Malformed(field, why))

private const val BASE64 = "must be standard base64"

private fun isBase64(text: String): Boolean =
    text.isNotEmpty() && runCatching { Base64.getDecoder().decode(text) }.isSuccess

fun parseMomentId(raw: String?): Resource<MomentId> =
    raw?.let(MomentId::parse)?.let { Resource.success(it) } ?: malformed("momentId")

fun parseMediaId(raw: String?): Resource<UUID> =
    raw?.let(Uuid7::parse)?.let { Resource.success(it) } ?: malformed("mediaId")

/** ST4's query: `after` a moment id, `limit` 1 to [pageMax] ([pageDefault] when absent). */
fun parseMomentsQuery(
    after: String?,
    limit: String?,
    pageDefault: Int,
    pageMax: Int,
): Resource<Pair<MomentId?, Int>> {
    val afterId = after?.let { MomentId.parse(it) ?: return malformed("after") }
    val size = limit?.let { it.toIntOrNull() ?: return malformed("limit") } ?: pageDefault
    if (size !in 1..pageMax) return malformed("limit", "1 to $pageMax")
    return Resource.success(afterId to size)
}

/** The kind's spelling is a shape the contract fixes as `status.media_invalid`, not `request.malformed`. */
fun PrepareMediaRequest.toCommand(poster: UserId): Resource<PrepareMediaCommand> {
    val parsed =
        MediaKind.entries.firstOrNull { it.name.lowercase() == kind } ?: return Resource.error(StatusError.MediaInvalid)
    return Resource.success(PrepareMediaCommand(poster, sizeBytes, parsed))
}

fun PostMomentRequest.toCommand(poster: UserId): Resource<PostMomentCommand> {
    val clientId = Uuid7.parse(clientMomentId) ?: return malformed("clientMomentId")
    if (!isBase64(ciphertext)) return malformed("ciphertext", BASE64)
    val people = audience.map { UserId.parse(it) ?: return malformed("audience", "each must be a user id") }
    val copies = keyCopies.map { it.toKeyCopy() ?: return malformed("keyCopies", "each needs a user id and $BASE64") }
    val media = mediaIds.map { Uuid7.parse(it) ?: return malformed("mediaIds", "each must be a media id") }
    return Resource.success(PostMomentCommand(poster, clientId, ciphertext, people, copies, media))
}

private fun KeyCopyDto.toKeyCopy(): KeyCopy? {
    val user = UserId.parse(userId) ?: return null
    if (!isBase64(ciphertext)) return null
    return KeyCopy(user, ciphertext)
}

/** ST5's body is optional: none, or `{}`, records a view without changing `reacted`. */
fun parseViewRequest(body: String): Resource<ViewMomentRequest> {
    if (body.isBlank()) return Resource.success(ViewMomentRequest())
    return try {
        Resource.success(CircasoJson.decodeFromString(ViewMomentRequest.serializer(), body))
    } catch (e: SerializationException) {
        malformed("reacted", "must be true or false")
    } catch (e: IllegalArgumentException) {
        malformed("reacted", "must be true or false")
    }
}

// ---- model → response ----

fun MediaUpload.toResponse() =
    MediaUploadResponse(mediaId.toString(), uploadUrl, UploadHeadersDto(contentType), expiresAt)

fun MomentMedia.toCompletedResponse() = MediaCompletedResponse(mediaId.toString(), sizeBytes)

fun MediaDownload.toResponse() = MediaDownloadResponse(downloadUrl, expiresAt, sizeBytes)

fun PostedMoment.toResponse() = PostedMomentResponse(momentId.toString(), postedAt, expiresAt)

fun MomentPage.toResponse() = MomentsResponse(moments.map { it.toDto() }, next?.toString())

private fun MomentView.toDto() =
    MomentDto(
        momentId = moment.momentId.toString(),
        posterId = moment.posterId.toString(),
        ciphertext = moment.ciphertext,
        keyCopy = keyCopy,
        mediaIds = listOfNotNull(moment.mediaId?.toString()),
        postedAt = moment.postedAt,
        expiresAt = moment.expiresAt,
        mine = mine,
        viewed = viewed,
        reacted = reacted,
        viewCount = viewCount,
    )

fun List<ViewRecord>.toResponse() = ViewsResponse(map { ViewDto(it.userId.toString(), it.viewedAt, it.reacted) })
