package com.circaso.calls.presentation.services

import com.circaso.calls.api.CallId
import com.circaso.calls.domain.models.CallReport
import com.circaso.calls.domain.models.CallView
import com.circaso.calls.domain.models.ConnectedCommand
import com.circaso.calls.domain.models.JoinCommand
import com.circaso.calls.domain.models.Joined
import com.circaso.calls.domain.models.LeaveCause
import com.circaso.calls.domain.models.MediaCommand
import com.circaso.calls.domain.models.PullCommand
import com.circaso.calls.domain.models.Pulled
import com.circaso.calls.domain.models.RenegotiateCommand
import com.circaso.calls.domain.usecases.DeclineCall
import com.circaso.calls.domain.usecases.GetCall
import com.circaso.calls.domain.usecases.GetLiveCall
import com.circaso.calls.domain.usecases.JoinCall
import com.circaso.calls.domain.usecases.LeaveCall
import com.circaso.calls.domain.usecases.PullTracks
import com.circaso.calls.domain.usecases.Renegotiate
import com.circaso.calls.domain.usecases.ReportConnected
import com.circaso.calls.domain.usecases.SendCallReport
import com.circaso.calls.domain.usecases.SetMedia
import com.circaso.calls.domain.usecases.StartCall
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** CL1 to CL10: start, look up, join, say connected, pull, renegotiate, decline, ring, leave, mute and report. */
class CallService(
    private val startCall: StartCall,
    private val getCall: GetCall,
    private val getLiveCall: GetLiveCall,
    private val joinCall: JoinCall,
    private val pullTracks: PullTracks,
    private val renegotiate: Renegotiate,
    private val declineCall: DeclineCall,
    private val ringAgain: com.circaso.calls.domain.usecases.RingAgain,
    private val leaveCall: LeaveCall,
    private val setMedia: SetMedia,
    private val reportConnected: ReportConnected,
    private val sendCallReport: SendCallReport,
) {
    suspend fun start(
        userId: UserId,
        chatId: ChatId,
        video: Boolean,
    ): Resource<CallView> = startCall(userId, chatId, video)

    suspend fun get(userId: UserId, callId: CallId): Resource<CallView> = getCall(userId, callId)

    suspend fun live(userId: UserId, chatId: ChatId): Resource<CallView> = getLiveCall(userId, chatId)

    suspend fun join(command: JoinCommand): Resource<Joined> = joinCall(command)

    suspend fun connected(command: ConnectedCommand): Resource<Unit> = reportConnected(command)

    suspend fun pull(command: PullCommand): Resource<Pulled> = pullTracks(command)

    suspend fun renegotiate(command: RenegotiateCommand): Resource<Unit> = renegotiate.invoke(command)

    suspend fun decline(userId: UserId, callId: CallId): Resource<CallView> = declineCall(userId, callId)

    suspend fun ring(
        userId: UserId,
        callId: CallId,
        userIds: Set<UserId>,
    ): Resource<CallView> = ringAgain(userId, callId, userIds)

    suspend fun leave(
        userId: UserId,
        callId: CallId,
        cause: LeaveCause?,
    ): Resource<CallView> = leaveCall(userId, callId, cause)

    suspend fun report(report: CallReport): Resource<Unit> = sendCallReport(report)

    suspend fun media(command: MediaCommand): Resource<Unit> = setMedia(command)
}
