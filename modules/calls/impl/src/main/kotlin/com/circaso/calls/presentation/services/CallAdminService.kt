package com.circaso.calls.presentation.services

import com.circaso.calls.api.CallId
import com.circaso.calls.domain.models.CallLinePage
import com.circaso.calls.domain.models.CallRecord
import com.circaso.calls.domain.usecases.GetCallRecord
import com.circaso.calls.domain.usecases.ListCallsOfUser
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** What staff read of calls through `CallsAdmin` (2026-11-13): a person's calls, and one call with its reports. */
class CallAdminService(private val listCallsOfUser: ListCallsOfUser, private val getCallRecord: GetCallRecord) {
    suspend fun callsOf(
        userId: UserId,
        cursor: String?,
        limit: Int,
    ): Resource<CallLinePage> = listCallsOfUser(userId, cursor, limit)

    suspend fun call(callId: CallId): Resource<CallRecord> = getCallRecord(callId)
}
