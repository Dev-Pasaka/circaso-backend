package com.circaso.calls.domain.usecases

import com.circaso.calls.domain.errors.CallsError
import com.circaso.calls.domain.models.CallView
import com.circaso.calls.domain.repositories.CallRepository
import com.circaso.calls.domain.support.CallViews
import com.circaso.calls.domain.support.People
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** `GET /chats/{chatId}/call`: the chat's call that is ringing or active, so a group shows "join call". */
class GetLiveCall(private val people: People, private val calls: CallRepository, private val views: CallViews) {
    suspend operator fun invoke(userId: UserId, chatId: ChatId): Resource<CallView> {
        val member = people.requireMember(chatId, userId)
        if (member is Resource.Error) return member
        val call = calls.live(chatId) ?: return Resource.error(CallsError.NotFound)
        return Resource.success(views.view(call))
    }
}
