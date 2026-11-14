package com.circaso.messaging.domain.repositories

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.GroupLinkToken
import com.circaso.messaging.domain.models.GroupLink
import com.circaso.messaging.domain.models.LinkPreview
import com.circaso.shared.UserId
import java.time.Instant

/**
 * Group join links: `group_links` (one live row per group, by the partial unique index) and `group_removals` (who an
 * admin removed, so a link can't bring them back). Writes that depend on the group run inside a `Transactor` block
 * after the chat's row is locked, like every membership change.
 */
interface GroupLinkRepository {
    /** The link by its token, live or not (a tombstone stays until the purge). */
    suspend fun find(token: GroupLinkToken): GroupLink?

    /** The group's live link, or null when it has none. */
    suspend fun liveOf(chatId: ChatId): GroupLink?

    /** Inserts [link] as the group's live one. The caller holds the chat's lock and checked there is none. */
    suspend fun insert(link: GroupLink)

    /** Replaces the live link's published preview; false when the link is no longer live. */
    suspend fun republish(
        token: GroupLinkToken,
        preview: LinkPreview,
        at: Instant,
    ): Boolean

    /**
     * Turns the group's live link off: the published preview is deleted in the same statement, and the row is kept
     * until [purgeAt]. True when there was one.
     */
    suspend fun revoke(
        chatId: ChatId,
        at: Instant,
        purgeAt: Instant,
    ): Boolean

    /** Notes that an admin removed [userId] from [chatId]. Inside the removal's transaction. */
    suspend fun recordRemoval(
        chatId: ChatId,
        userId: UserId,
        by: UserId,
        at: Instant,
    )

    /** Whether an admin removed [userId] from [chatId] and no admin has brought them back since. */
    suspend fun wasRemoved(chatId: ChatId, userId: UserId): Boolean

    /** Forgets the removal: an admin invited them again and they accepted. */
    suspend fun clearRemoval(chatId: ChatId, userId: UserId)
}
