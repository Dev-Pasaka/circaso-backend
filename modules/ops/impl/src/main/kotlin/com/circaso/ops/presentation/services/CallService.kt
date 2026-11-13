package com.circaso.ops.presentation.services

import com.circaso.calls.api.CallId
import com.circaso.calls.api.StaffCallPage
import com.circaso.ops.domain.models.CallSheet
import com.circaso.ops.domain.usecases.GetCallSheet
import com.circaso.ops.domain.usecases.ListUserCalls
import com.circaso.shared.Resource
import com.circaso.shared.UserId

class CallService(private val listUserCalls: ListUserCalls, private val getCallSheet: GetCallSheet) {
    suspend fun ofUser(
        userId: UserId,
        cursor: String?,
        limit: Int,
    ): Resource<StaffCallPage> = listUserCalls(userId, cursor, limit)

    suspend fun get(callId: CallId): Resource<CallSheet> = getCallSheet(callId)
}
