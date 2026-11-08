package com.circaso.messaging.domain.repositories

import com.circaso.messaging.domain.models.Attachment
import java.time.Instant
import java.util.UUID

/** `attachments`: one row per encrypted file, from AT1 until it expires. */
interface AttachmentRepository {
    suspend fun insert(attachment: Attachment)

    suspend fun find(attachmentId: UUID): Attachment?

    /** `PENDING` → `READY`, in one conditional update. False when the row was not pending (or is gone). */
    suspend fun markReady(attachmentId: UUID): Boolean

    /**
     * Up to [limit] rows to purge: expired at [now], or still `PENDING` and created before [pendingBefore]. Oldest
     * first, so a backlog drains in order.
     */
    suspend fun purgeable(
        now: Instant,
        pendingBefore: Instant,
        limit: Int,
    ): List<Attachment>

    /**
     * Makes [attachmentId] a group's photo: clears its expiry (`SET expires_at = NULL`) if it is `READY` and live at
     * [now]. False when it isn't (or is gone), so a purge that got there first can't be undone. Inside the M8
     * transaction, after the chat's row is locked.
     */
    suspend fun keepAsGroupPhoto(attachmentId: UUID, now: Instant): Boolean

    /**
     * A group's former photo: gives it an expiry again, [purgeAt], if it has none. False when it already had one, so a
     * retried release never moves a deadline.
     */
    suspend fun releaseGroupPhoto(attachmentId: UUID, purgeAt: Instant): Boolean

    /** True when the row was there and is now deleted. */
    suspend fun delete(attachmentId: UUID): Boolean
}
