package com.circaso.status.domain.models

import com.circaso.shared.UserId
import com.circaso.status.api.MomentId
import java.time.Instant
import java.util.UUID

/**
 * One moment as the server keeps it: ciphertext the poster's phone made under its moments sender key, readable by
 * nobody here, live from [postedAt] until [expiresAt] (24 hours). [mediaId] is the one encrypted file it carries.
 */
data class Moment(
    val momentId: MomentId,
    val posterId: UserId,
    val clientMomentId: UUID,
    val ciphertext: String,
    val mediaId: UUID?,
    val postedAt: Instant,
    val expiresAt: Instant,
)

/**
 * One person a moment was shown to: their pairwise copy of the poster's sender key when one was sent with it, and,
 * once they opened it, when and whether they reacted.
 */
data class AudienceMember(
    val userId: UserId,
    val keyCopy: String?,
    val viewedAt: Instant? = null,
    val reacted: Boolean = false,
)

/** A pairwise-encrypted copy of the poster's moments sender key for one audience member. */
data class KeyCopy(val userId: UserId, val ciphertext: String)

data class PostMomentCommand(
    val poster: UserId,
    val clientMomentId: UUID,
    val ciphertext: String,
    val audience: List<UserId>,
    val keyCopies: List<KeyCopy>,
    val mediaIds: List<UUID>,
)

/** ST3's answer: the same one again for a repeat of the same `clientMomentId`. */
data class PostedMoment(val momentId: MomentId, val postedAt: Instant, val expiresAt: Instant)

/** A moment as one caller sees it in ST4. [viewCount] is only for the caller's own. */
data class MomentView(
    val moment: Moment,
    val keyCopy: String?,
    val mine: Boolean,
    val viewed: Boolean,
    val reacted: Boolean,
    val viewCount: Int?,
)

data class MomentPage(val moments: List<MomentView>, val next: MomentId?)

/** One row of ST6. */
data class ViewRecord(val userId: UserId, val viewedAt: Instant, val reacted: Boolean)

/** What ST5 changed: [changed] is false for a repeat that set nothing new, which sends no frame. */
data class ViewOutcome(val viewedAt: Instant, val reacted: Boolean, val changed: Boolean)

/** The phone's word for what a moment's file is; the real type travels inside the encrypted body. */
enum class MediaKind { IMAGE, VIDEO, VOICE }

/** `PENDING` from ST1 until ST2 finds the object at the size promised, then `READY`. */
enum class MediaState { PENDING, READY }

/**
 * One encrypted file for a moment. Until ST3 attaches it, [expiresAt] is an hour after ST1; once attached it is the
 * moment's own expiry, and deleting the moment brings it forward to now. The sweep deletes every file past it.
 */
data class MomentMedia(
    val mediaId: UUID,
    val posterId: UserId,
    val kind: MediaKind,
    val sizeBytes: Long,
    val state: MediaState,
    val momentId: MomentId?,
    val createdAt: Instant,
    val expiresAt: Instant,
)

data class PrepareMediaCommand(val poster: UserId, val sizeBytes: Long, val kind: MediaKind)

/** ST1's answer: PUT the ciphertext to [uploadUrl] with `Content-Type: [contentType]` before [expiresAt]. */
data class MediaUpload(
    val mediaId: UUID,
    val uploadUrl: String,
    val contentType: String,
    val expiresAt: Instant,
)

/** ST8's answer: a short-lived link, never stored. */
data class MediaDownload(val downloadUrl: String, val expiresAt: Instant, val sizeBytes: Long)

/** Every blob is ciphertext, so storage only ever sees this type. */
const val MEDIA_CONTENT_TYPE = "application/octet-stream"
