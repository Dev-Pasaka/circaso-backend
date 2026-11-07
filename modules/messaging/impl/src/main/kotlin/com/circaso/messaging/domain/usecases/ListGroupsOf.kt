package com.circaso.messaging.domain.usecases

import com.circaso.messaging.domain.models.Chat
import com.circaso.messaging.domain.repositories.GroupReportRepository
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** Staff: the groups a person is in, newest first (decision 2026-11-08), at most [limit]. */
class ListGroupsOf(private val reports: GroupReportRepository) {
    suspend operator fun invoke(userId: UserId, limit: Int): Resource<List<Chat>> =
        Resource.success(reports.groupsOf(userId, limit.coerceIn(1, MAX_GROUPS)))

    private companion object {
        const val MAX_GROUPS = 200
    }
}
