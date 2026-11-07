package com.circaso.messaging.domain.usecases

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.models.Chat
import com.circaso.messaging.domain.repositories.GroupReportRepository
import com.circaso.shared.Resource

/** Staff: the groups among [ids] that still exist (decision 2026-11-08); a direct chat's id finds nothing. */
class GetGroupsByIds(private val reports: GroupReportRepository) {
    suspend operator fun invoke(ids: Set<ChatId>): Resource<Map<ChatId, Chat>> {
        if (ids.isEmpty()) return Resource.success(emptyMap())
        return Resource.success(reports.groups(ids.take(MAX_IDS).toSet()).associateBy { it.chatId })
    }

    private companion object {
        /** A page of results at most; the ops module never asks for more at once. */
        const val MAX_IDS = 500
    }
}
