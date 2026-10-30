package com.circaso.calls.presentation.services

import com.circaso.calls.api.CallId
import com.circaso.calls.domain.ports.LoggedFrame
import com.circaso.calls.domain.usecases.ExpireRings
import com.circaso.calls.domain.usecases.FindLiveCall
import com.circaso.calls.domain.usecases.ReplayFrames
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** What other parts of the system ask or tell calls: the gateway's replay, the ring timeout, its `api`. */
class CallEventsService(
    private val replayFrames: ReplayFrames,
    private val expireRings: ExpireRings,
    private val findLiveCall: FindLiveCall,
) {
    suspend fun missedFrames(userId: UserId, seq: Long): Resource<List<LoggedFrame>?> = replayFrames(userId, seq)

    suspend fun expireRings(): Int = expireRings.invoke()

    suspend fun liveCall(chatId: ChatId): Resource<CallId?> = findLiveCall(chatId)
}
