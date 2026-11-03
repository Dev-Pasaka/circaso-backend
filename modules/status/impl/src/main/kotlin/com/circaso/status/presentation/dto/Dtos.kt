package com.circaso.status.presentation.dto

import com.circaso.shared.InstantSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
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
 * sent with this moment. `reaction` is the caller's own emoji on someone else's moment (`reacted` is
 * `reaction != null`); `viewCount` and `reactionCount` are only on the caller's own (`mine`).
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
    val reaction: String?,
    val viewCount: Int?,
    val reactionCount: Int?,
)

/** ST4. `next` is the `after` of the next page; null on the last. */
@Serializable
data class MomentsResponse(val moments: List<MomentDto>, val next: String?)

/**
 * ST5. `reaction` sets the caller's emoji, `"reaction": null` clears it and no `reaction` leaves it, so the field is
 * a [ReactionField] that tells the three apart. `reaction` wins over `reacted`; `reacted: true` alone is the heart,
 * `false` clears. Leave both out to record a view without changing anything.
 */
@Serializable
data class ViewMomentRequest(val reacted: Boolean? = null, val reaction: ReactionField = ReactionField.Absent)

/** ST5's `reaction` as sent: a string, an explicit `null`, or not there at all. */
@Serializable(with = ReactionFieldSerializer::class)
sealed interface ReactionField {
    data object Absent : ReactionField

    data object Null : ReactionField

    data class Value(val text: String) : ReactionField
}

/** On the wire a string or null; an absent key never reaches it, so the property's default stands for that. */
@OptIn(ExperimentalSerializationApi::class)
object ReactionFieldSerializer : KSerializer<ReactionField> {
    override val descriptor: SerialDescriptor = String.serializer().nullable.descriptor

    override fun deserialize(decoder: Decoder): ReactionField =
        if (decoder.decodeNotNullMark()) {
            ReactionField.Value(decoder.decodeString())
        } else {
            decoder.decodeNull()
            ReactionField.Null
        }

    override fun serialize(encoder: Encoder, value: ReactionField) {
        when (value) {
            is ReactionField.Value -> encoder.encodeString(value.text)
            else -> encoder.encodeNull()
        }
    }
}

@Serializable
data class ViewedResponse(val viewed: Boolean)

@Serializable
data class ViewDto(
    val userId: String,
    @Serializable(with = InstantSerializer::class) val viewedAt: Instant,
    val reacted: Boolean,
    val reaction: String?,
)

@Serializable
data class ViewsResponse(val views: List<ViewDto>)

@Serializable
data class DeletedResponse(val deleted: Boolean)

/** `POST /moments/keys/request`: the posters whose moments this phone can't open, 1 to 100, never yourself. */
@Serializable
data class KeyRequestRequest(val posterIds: List<String>)

/** The posters who were asked; the others have nothing live for you, or were asked in the last 10 minutes. */
@Serializable
data class KeyRequestResponse(val requested: List<String>)

/** One fresh copy for `userId` on one of your live moments: the body sealed again over your 1:1 session with them. */
@Serializable
data class MomentKeyCopyDto(val momentId: String, val userId: String, val ciphertext: String)

/** `POST /moments/key-copies`: 1 to 200 copies, one per (moment, person). */
@Serializable
data class KeyCopiesRequest(val copies: List<MomentKeyCopyDto>)

@Serializable
data class KeyCopiesResponse(val copied: Int)
