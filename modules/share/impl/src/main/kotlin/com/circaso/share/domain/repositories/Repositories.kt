package com.circaso.share.domain.repositories

import com.circaso.share.api.ShareCode
import com.circaso.share.domain.models.LinkCursor
import com.circaso.share.domain.models.ShareContent
import com.circaso.share.domain.models.ShareLink
import com.circaso.shared.UserId
import java.time.Instant

/**
 * `share.links`. A row lives until its `expires_at` (null while the link may still be opened): 30 days after the link
 * ends or is revoked, so the page can say it was turned off rather than that it never existed; then the purge job
 * deletes it.
 */
interface LinkRepository {
    /** Inserts [link] unless its code is taken; true if this call inserted it. */
    suspend fun insert(link: ShareLink, expiresAt: Instant?): Boolean

    /** The row behind [code] while it is kept (live, ended or revoked), or null. */
    suspend fun find(code: ShareCode, now: Instant): ShareLink?

    /** [ownerId]'s live links, newest first, after [after]. */
    suspend fun listLive(
        ownerId: UserId,
        now: Instant,
        after: LinkCursor?,
        limit: Int,
    ): List<ShareLink>

    /**
     * Turns off [ownerId]'s live link [code]: clears its snapshot, stamps `revoked_at` and keeps the row until
     * [keepUntil]. False when there is no such live link of theirs.
     */
    suspend fun revoke(
        code: ShareCode,
        ownerId: UserId,
        now: Instant,
        keepUntil: Instant,
    ): Boolean

    /** Replaces a live link's snapshot (a receipt whose payment settled since). False when it is no longer live. */
    suspend fun updateContent(code: ShareCode, content: ShareContent): Boolean

    /** Every link [ownerId] made, gone at once (their account was deleted). */
    suspend fun deleteOwnedBy(ownerId: UserId): Int
}
