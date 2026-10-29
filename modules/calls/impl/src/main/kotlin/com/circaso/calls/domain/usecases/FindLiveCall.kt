package com.circaso.calls.domain.usecases

import com.circaso.calls.api.CallId
import com.circaso.calls.domain.repositories.CallRepository
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Resource

/** `Calls.liveCall`, for other modules: the chat's call that hasn't ended. */
class FindLiveCall(private val calls: CallRepository) {
    suspend operator fun invoke(chatId: ChatId): Resource<CallId?> = Resource.success(calls.live(chatId)?.callId)
}
