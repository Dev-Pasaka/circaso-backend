package com.circaso.messaging.domain.usecases

import com.circaso.messaging.domain.models.GroupKey
import com.circaso.messaging.domain.models.GroupResults
import com.circaso.messaging.domain.repositories.GroupReportRepository
import com.circaso.messaging.domain.support.GroupCursors
import com.circaso.shared.Resource
import com.circaso.shared.SharedError

/** Staff: every group, newest first (decision 2026-11-08), by keyset on `(created_at, chat_id)`, never by offset. */
class ListGroups(private val reports: GroupReportRepository) {
    suspend operator fun invoke(cursor: String?, limit: Int): Resource<GroupResults> {
        val after =
            cursor?.let {
                GroupCursors.decode(it) ?: return Resource.error(SharedError.Malformed("cursor", "invalid"))
            }
        val page = limit.coerceIn(1, MAX_PAGE)
        val found = reports.newest(after, page + 1)
        val shown = found.take(page)
        val next =
            if (found.size >
                page
            ) {
                shown.last().let { GroupCursors.encode(GroupKey(it.createdAt, it.chatId)) }
            } else {
                null
            }
        return Resource.success(GroupResults(shown, next))
    }

    private companion object {
        const val MAX_PAGE = 200
    }
}
