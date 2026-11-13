package com.circaso.calls.domain.usecases

import com.circaso.calls.api.CallId
import com.circaso.calls.domain.models.CallLinePage
import com.circaso.calls.domain.repositories.CallRepository
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId

/**
 * For staff (ops, `calls.read`): the calls a person was rung for or joined, newest first. The cursor is the last call
 * id of the page before; anything else is `request.malformed`.
 */
class ListCallsOfUser(private val calls: CallRepository) {
    suspend operator fun invoke(
        userId: UserId,
        cursor: String?,
        limit: Int,
    ): Resource<CallLinePage> {
        val before =
            if (cursor == null) {
                null
            } else {
                CallId.parse(cursor) ?: return Resource.error(SharedError.Malformed("cursor", "is not a cursor"))
            }
        val rows = calls.ofUser(userId, before, limit + 1)
        val page = rows.take(limit)
        val next = if (rows.size > limit) page.last().callId.toString() else null
        return Resource.success(CallLinePage(page, next))
    }
}
