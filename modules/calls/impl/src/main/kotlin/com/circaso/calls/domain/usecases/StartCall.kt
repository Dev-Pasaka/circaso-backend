package com.circaso.calls.domain.usecases

import com.circaso.calls.api.CallId
import com.circaso.calls.domain.errors.CallsError
import com.circaso.calls.domain.models.Call
import com.circaso.calls.domain.models.CallKind
import com.circaso.calls.domain.models.CallState
import com.circaso.calls.domain.models.CallView
import com.circaso.calls.domain.ports.CallFrame
import com.circaso.calls.domain.ports.FrameSender
import com.circaso.calls.domain.repositories.CallRepository
import com.circaso.calls.domain.support.CallRules
import com.circaso.calls.domain.support.CallViews
import com.circaso.calls.domain.support.People
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Clock

/**
 * CL1 `POST /calls`: a member calls the chat. Everyone else in it is rung (`call.ringing`, pushed data-only to a
 * phone without a socket); the caller joins with CL3. A chat has one call at a time: when one is ringing or active,
 * this answers `calls.already_in_call` and the app joins that one instead.
 */
class StartCall(
    private val people: People,
    private val calls: CallRepository,
    private val views: CallViews,
    private val frames: FrameSender,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        userId: UserId,
        chatId: ChatId,
        video: Boolean,
    ): Resource<CallView> {
        val members =
            when (val r = people.requireMember(chatId, userId)) {
                is Resource.Error -> return r
                is Resource.Success -> r.data
            }
        val now = clock.instant()
        val call =
            Call(
                callId = CallId.new(),
                chatId = chatId,
                kind = if (video) CallKind.VIDEO else CallKind.VOICE,
                state = CallState.RINGING,
                startedBy = userId,
                isGroup = people.isGroup(chatId),
                createdAt = now,
                startedAt = null,
                endedAt = null,
                endReason = null,
                activeAt = now,
                participants = members.map { CallRules.invited(it, now).copy(video = video) },
            )
        if (!calls.insert(call)) return Resource.error(CallsError.AlreadyInCall)

        val ringing = CallFrame.Ringing(call.callId, chatId, call.kind, userId, people.name(userId))
        frames.send(members - userId, ringing)
        return Resource.success(views.view(call))
    }
}
