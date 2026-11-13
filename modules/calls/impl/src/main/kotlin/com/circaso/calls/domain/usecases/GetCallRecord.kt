package com.circaso.calls.domain.usecases

import com.circaso.calls.api.CallId
import com.circaso.calls.domain.errors.CallsError
import com.circaso.calls.domain.models.CallRecord
import com.circaso.calls.domain.repositories.CallReportRepository
import com.circaso.calls.domain.repositories.CallRepository
import com.circaso.shared.Resource

/** For staff (ops, `calls.read`): one call with every participant and the report each phone sent. */
class GetCallRecord(private val calls: CallRepository, private val reports: CallReportRepository) {
    suspend operator fun invoke(callId: CallId): Resource<CallRecord> {
        val call = calls.find(callId) ?: return Resource.error(CallsError.NotFound)
        return Resource.success(CallRecord(call, reports.forCall(callId)))
    }
}
