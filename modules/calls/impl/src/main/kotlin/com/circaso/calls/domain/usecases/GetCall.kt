package com.circaso.calls.domain.usecases

import com.circaso.calls.api.CallId
import com.circaso.calls.domain.errors.CallsError
import com.circaso.calls.domain.models.CallView
import com.circaso.calls.domain.repositories.CallRepository
import com.circaso.calls.domain.support.CallViews
import com.circaso.calls.domain.support.People
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** CL2 `GET /calls/{callId}`: the call, for the chat's members only. */
class GetCall(private val people: People, private val calls: CallRepository, private val views: CallViews) {
    suspend operator fun invoke(userId: UserId, callId: CallId): Resource<CallView> {
        val call = calls.find(callId) ?: return Resource.error(CallsError.NotFound)
        val member = people.requireMember(call.chatId, userId)
        if (member is Resource.Error) return member
        return Resource.success(views.view(call))
    }
}
