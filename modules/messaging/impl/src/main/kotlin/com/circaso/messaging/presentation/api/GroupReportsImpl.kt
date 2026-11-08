package com.circaso.messaging.presentation.api

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.GroupPage
import com.circaso.messaging.api.GroupReports
import com.circaso.messaging.api.OpsGroup
import com.circaso.messaging.api.OpsGroupMember
import com.circaso.messaging.domain.models.Chat
import com.circaso.messaging.presentation.services.GroupReportService
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.getOrNull
import com.circaso.shared.getOrThrow
import com.circaso.shared.map

/**
 * messaging:api's staff contract (decision 2026-11-08), bound for the ops module only: the app gateway never mounts it.
 * Ids, counts and roles only; a group's sealed name and photo never leave the module. Every call goes through
 * [GroupReportService].
 */
class GroupReportsImpl(private val reports: GroupReportService) : GroupReports {
    override suspend fun groups(cursor: String?, limit: Int): Resource<GroupPage> =
        reports.groups(cursor, limit).map { page -> GroupPage(page.groups.map { it.toOps() }, page.nextCursor) }

    override suspend fun groupsByIds(ids: Set<ChatId>): Map<ChatId, OpsGroup> =
        reports
            .groupsByIds(ids)
            .getOrNull()
            .orEmpty()
            .mapValues { (_, chat) -> chat.toOps() }

    override suspend fun members(chatId: ChatId, limit: Int): List<OpsGroupMember> =
        reports
            .members(chatId, limit)
            .getOrNull()
            .orEmpty()
            .map { OpsGroupMember(it.member.userId, it.role.name, it.member.joinedAt) }

    override suspend fun groupsOf(userId: UserId, limit: Int): List<OpsGroup> =
        reports
            .groupsOf(userId, limit)
            .getOrNull()
            .orEmpty()
            .map { it.toOps() }

    override suspend fun groupCount(): Long = reports.groupCount().getOrThrow()

    private fun Chat.toOps() = OpsGroup(chatId, memberCount, ownerId, createdAt)
}
