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
 * this answers that call, and the caller joins it with CL3 like anyone answering it (2026-11-09; it was
 * `calls.already_in_call`, 409). So two people calling each other at the same moment ("glare") end up in one call,
 * nobody is rung twice, and calling again straight after a hang-up starts a new call, since the hang-up has already
 * ended the old one (CL7 answers at once).
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
        if (!calls.insert(call)) {
            calls.live(chatId)?.let { return Resource.success(views.view(it)) }
            // the live call ended between the two reads: this one can start now
            if (!calls.insert(call)) {
                return calls.live(chatId)?.let { Resource.success(views.view(it)) }
                    ?: Resource.error(CallsError.AlreadyInCall)
            }
        }

        val ringing = CallFrame.Ringing(call.callId, chatId, call.kind, userId, people.name(userId))
        frames.send(members - userId, ringing)
        return Resource.success(views.view(call))
    }
}
