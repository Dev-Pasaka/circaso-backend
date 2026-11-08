package com.circaso.messaging.api

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Instant

// Messaging's staff-facing contract (decision 2026-11-08), for the ops module behind the ops gateway only. A group's
// name and photo are sealed under the group's key: the server never knows them, so staff see ids and counts only.

data class OpsGroup(
    val chatId: ChatId,
    val memberCount: Int,
    val ownerId: UserId?,
    val createdAt: Instant,
)

/** [role] is `OWNER`, `ADMIN` or `MEMBER`. */
data class OpsGroupMember(val userId: UserId, val role: String, val joinedAt: Instant)

data class GroupPage(val items: List<OpsGroup>, val nextCursor: String?)

interface GroupReports {
    /** Every group, newest first. Error: `request.malformed` for a cursor that isn't one. [limit] 1-200. */
    suspend fun groups(cursor: String?, limit: Int): Resource<GroupPage>

    /** The groups among [ids] that still exist. */
    suspend fun groupsByIds(ids: Set<ChatId>): Map<ChatId, OpsGroup>

    /** Members, owner first then by joining, at most [limit]. Empty for a chat that isn't a group. */
    suspend fun members(chatId: ChatId, limit: Int): List<OpsGroupMember>

    /** The groups [userId] is in, newest first, at most [limit]. */
    suspend fun groupsOf(userId: UserId, limit: Int): List<OpsGroup>

    suspend fun groupCount(): Long
}
