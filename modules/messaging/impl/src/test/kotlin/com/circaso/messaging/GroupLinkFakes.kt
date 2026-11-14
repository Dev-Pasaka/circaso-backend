package com.circaso.messaging

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.GroupLinkToken
import com.circaso.messaging.domain.models.GroupLink
import com.circaso.messaging.domain.models.LinkPreview
import com.circaso.messaging.domain.ports.LinkRateLimit
import com.circaso.messaging.domain.repositories.GroupLinkRepository
import com.circaso.shared.UserId
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Group join links in memory, with the table's rules: one live link per group, a revoked one emptied of its preview,
 * and links and removals going with their chat, as `ON DELETE CASCADE` does.
 */
class MemoryGroupLinks(private val chats: MemoryChats) : GroupLinkRepository {
    val links = ConcurrentHashMap<GroupLinkToken, GroupLink>()
    val removals: MutableSet<Pair<ChatId, UserId>> = ConcurrentHashMap.newKeySet()

    override suspend fun find(token: GroupLinkToken) = links[token]?.takeIf { chats.chats.containsKey(it.chatId) }

    override suspend fun liveOf(chatId: ChatId) =
        links.values.firstOrNull { it.chatId == chatId && it.revokedAt == null && chats.chats.containsKey(chatId) }

    override suspend fun insert(link: GroupLink) {
        synchronized(this) {
            check(links.values.none { it.chatId == link.chatId && it.revokedAt == null }) { "a second live link" }
            links[link.token] = link
        }
    }

    override suspend fun republish(
        token: GroupLinkToken,
        preview: LinkPreview,
        at: Instant,
    ): Boolean =
        synchronized(this) {
            val stored = links[token]?.takeIf { it.revokedAt == null } ?: return false
            links[token] = stored.copy(preview = preview, updatedAt = at)
            true
        }

    override suspend fun revoke(
        chatId: ChatId,
        at: Instant,
        purgeAt: Instant,
    ): Boolean =
        synchronized(this) {
            val live = links.values.firstOrNull { it.chatId == chatId && it.revokedAt == null } ?: return false
            links[live.token] = live.copy(preview = null, revokedAt = at)
            true
        }

    override suspend fun recordRemoval(
        chatId: ChatId,
        userId: UserId,
        by: UserId,
        at: Instant,
    ) {
        removals += chatId to userId
    }

    override suspend fun wasRemoved(chatId: ChatId, userId: UserId) =
        chats.chats.containsKey(chatId) && (chatId to userId) in removals

    override suspend fun clearRemoval(chatId: ChatId, userId: UserId) {
        removals -= chatId to userId
    }
}

/** Counts in memory; a test sets [max] hits by calling until it refuses. */
class MemoryLinkRateLimit : LinkRateLimit {
    private val counts = ConcurrentHashMap<String, Int>()

    override suspend fun take(
        action: String,
        userId: UserId,
        max: Int,
    ): Long? = if (counts.merge("$action:$userId", 1, Int::plus)!! <= max) null else RETRY_AFTER

    companion object {
        const val RETRY_AFTER = 1_234L
    }
}
