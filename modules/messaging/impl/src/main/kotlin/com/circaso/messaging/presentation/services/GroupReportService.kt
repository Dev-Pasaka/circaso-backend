package com.circaso.messaging.presentation.services

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.models.Chat
import com.circaso.messaging.domain.models.GroupMemberView
import com.circaso.messaging.domain.models.GroupResults
import com.circaso.messaging.domain.usecases.CountGroups
import com.circaso.messaging.domain.usecases.GetGroupsByIds
import com.circaso.messaging.domain.usecases.ListGroupMembers
import com.circaso.messaging.domain.usecases.ListGroups
import com.circaso.messaging.domain.usecases.ListGroupsOf
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** Staff on the ops gateway (decision 2026-11-08): groups, their members and how many there are. */
class GroupReportService(
    private val listGroups: ListGroups,
    private val getGroupsByIds: GetGroupsByIds,
    private val listGroupMembers: ListGroupMembers,
    private val listGroupsOf: ListGroupsOf,
    private val countGroups: CountGroups,
) {
    suspend fun groups(cursor: String?, limit: Int): Resource<GroupResults> = listGroups(cursor, limit)

    suspend fun groupsByIds(ids: Set<ChatId>): Resource<Map<ChatId, Chat>> = getGroupsByIds(ids)

    suspend fun members(chatId: ChatId, limit: Int): Resource<List<GroupMemberView>> = listGroupMembers(chatId, limit)

    suspend fun groupsOf(userId: UserId, limit: Int): Resource<List<Chat>> = listGroupsOf(userId, limit)

    suspend fun groupCount(): Resource<Long> = countGroups()
}
