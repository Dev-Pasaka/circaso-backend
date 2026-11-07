package com.circaso.messaging.domain.repositories

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.models.Chat
import com.circaso.messaging.domain.models.GroupKey
import com.circaso.shared.UserId

/**
 * Staff reads over groups (decision 2026-11-08). They run in the ops process on the app's own pools, so each is a short
 * keyset page or a bounded read served by an index; nothing here writes or locks.
 */
interface GroupReportRepository {
    /** Groups newest first, strictly after [after] in `(created_at, chat_id) DESC` order (`chats_groups_newest_idx`). */
    suspend fun newest(after: GroupKey?, limit: Int): List<Chat>

    /** Those of [ids] that are groups and still exist. */
    suspend fun groups(ids: Set<ChatId>): List<Chat>

    /** The groups [userId] is in, newest first (`members_user_idx`, then each chat by its key), at most [limit]. */
    suspend fun groupsOf(userId: UserId, limit: Int): List<Chat>

    suspend fun count(): Long
}
