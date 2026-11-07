package com.circaso.ops.presentation.services

import com.circaso.messaging.api.ChatId
import com.circaso.ops.domain.models.GroupDetail
import com.circaso.ops.domain.models.GroupSummaryPage
import com.circaso.ops.domain.models.WalletFilter
import com.circaso.ops.domain.usecases.GetGroup
import com.circaso.ops.domain.usecases.ListGroups
import com.circaso.shared.Resource

class GroupService(private val listGroups: ListGroups, private val getGroup: GetGroup) {
    suspend fun list(
        filter: WalletFilter,
        cursor: String?,
        limit: Int,
    ): Resource<GroupSummaryPage> = listGroups(filter, cursor, limit)

    suspend fun get(chatId: ChatId, withMoney: Boolean): Resource<GroupDetail> = getGroup(chatId, withMoney)
}
