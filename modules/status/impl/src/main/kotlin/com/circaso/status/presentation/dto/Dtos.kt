package com.circaso.status.presentation.dto

import com.circaso.shared.InstantSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/** ST1. `kind` is `image`, `video` or `voice`; `sizeBytes` is the ciphertext's exact size, 1 B to 32 MiB. */
@Serializable
data class PrepareMediaRequest(val sizeBytes: Long, val kind: String)

/** The headers the PUT must carry exactly, or storage refuses the signature. */
@Serializable
data class UploadHeadersDto(
    @SerialName("Content-Type") val contentType: String,
)

@Serializable
data class MediaUploadResponse(
    val mediaId: String,
    val uploadUrl: String,
    val uploadHeaders: UploadHeadersDto,
    @Serializable(with = InstantSerializer::class) val expiresAt: Instant,
)

@Serializable
data class MediaCompletedResponse(val mediaId: String, val sizeBytes: Long)

@Serializable
data class MediaDownloadResponse(
    val downloadUrl: String,
    @Serializable(with = InstantSerializer::class) val expiresAt: Instant,
    val sizeBytes: Long,
)

/** One audience member's pairwise-encrypted copy of the poster's moments sender key. */
@Serializable
data class KeyCopyDto(val userId: String, val ciphertext: String)

/**
 * ST3. `clientMomentId` is a UUID made on the phone; `ciphertext` the body encrypted once under the poster's sender
 * key (standard base64, at most 64 KiB decoded); `audience` 1 to 1,024 user ids; `keyCopies` only for audience
 * members; `mediaIds` at most one completed file.
 */
@Serializable
data class PostMomentRequest(
    val clientMomentId: String,
    val ciphertext: String,
    val audience: List<String>,
    val keyCopies: List<KeyCopyDto> = emptyList(),
    val mediaIds: List<String> = emptyList(),
)

@Serializable
data class PostedMomentResponse(
    val momentId: String,
    @Serializable(with = InstantSerializer::class) val postedAt: Instant,
    @Serializable(with = InstantSerializer::class) val expiresAt: Instant,
)

/**
 * One moment as the caller sees it. `keyCopy` is the caller's own copy of the poster's sender key, when one was
 * sent with this moment; `viewCount` is only on the caller's own (`mine`).
 */
@Serializable
data class MomentDto(
    val momentId: String,
    val posterId: String,
    val ciphertext: String,
    val keyCopy: String?,
    val mediaIds: List<String>,
    @Serializable(with = InstantSerializer::class) val postedAt: Instant,
    @Serializable(with = InstantSerializer::class) val expiresAt: Instant,
    val mine: Boolean,
    val viewed: Boolean,
    val reacted: Boolean,
    val viewCount: Int?,
)

/** ST4. `next` is the `after` of the next page; null on the last. */
@Serializable
data class MomentsResponse(val moments: List<MomentDto>, val next: String?)

/** ST5. Leave `reacted` out to record a view without changing it. */
@Serializable
data class ViewMomentRequest(val reacted: Boolean? = null)

@Serializable
data class ViewedResponse(val viewed: Boolean)

@Serializable
data class ViewDto(
    val userId: String,
    @Serializable(with = InstantSerializer::class) val viewedAt: Instant,
    val reacted: Boolean,
)

@Serializable
data class ViewsResponse(val views: List<ViewDto>)

@Serializable
data class DeletedResponse(val deleted: Boolean)
