package com.circaso.ops.domain.usecases

import com.circaso.calls.api.CallId
import com.circaso.calls.api.CallsAdmin
import com.circaso.calls.api.StaffCallPage
import com.circaso.ops.domain.errors.OpsError
import com.circaso.ops.domain.models.CallSheet
import com.circaso.ops.domain.support.UserViews
import com.circaso.shared.Resource
import com.circaso.shared.UserId

// Calls for SUPPORT and ADMIN (2026-11-13, `calls.read`): why a call dropped, from what each phone said when it left
// and the report it sent after. Read-only, so not audited; a report holds no message content, numbers or names.

class ListUserCalls(private val calls: CallsAdmin) {
    suspend operator fun invoke(
        userId: UserId,
        cursor: String?,
        limit: Int,
    ): Resource<StaffCallPage> = calls.callsOf(userId, cursor, limit)
}

class GetCallSheet(private val calls: CallsAdmin, private val views: UserViews) {
    suspend operator fun invoke(callId: CallId): Resource<CallSheet> {
        val call = calls.call(callId) ?: return Resource.error(OpsError.CallNotFound)
        val people = views.rows(call.participants.map { it.userId }).associateBy { it.userId }
        return Resource.success(CallSheet(call, people))
    }
}
