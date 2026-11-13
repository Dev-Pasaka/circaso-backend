package com.circaso.ops

import com.circaso.calls.api.CallId
import com.circaso.calls.api.CallsAdmin
import com.circaso.calls.api.StaffCall
import com.circaso.calls.api.StaffCallLine
import com.circaso.calls.api.StaffCallPage
import com.circaso.calls.api.StaffCallParticipant
import com.circaso.calls.api.StaffCallReport
import com.circaso.calls.api.StaffReportEvent
import com.circaso.calls.api.StaffReportSample
import com.circaso.calls.api.StaffReportSummary
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import java.util.concurrent.ConcurrentHashMap

/** calls' staff side in memory: a dropped 1:1 call between two people, one of whom sent a report. */
class FakeCallsAdmin : CallsAdmin {
    val calls = ConcurrentHashMap<CallId, StaffCall>()

    fun dropped(caller: UserId, callee: UserId): CallId {
        val callId = CallId(Uuid7.generate())
        val line =
            StaffCallLine(
                callId = callId,
                chatId = ChatId(Uuid7.generate()),
                kind = "VOICE",
                state = "ENDED",
                endReason = "DROPPED",
                isGroup = false,
                startedBy = caller,
                createdAt = EPOCH,
                startedAt = EPOCH.plusSeconds(5),
                endedAt = EPOCH.plusSeconds(167),
                participants = 2,
                reports = 1,
            )
        val report =
            StaffCallReport(
                platform = "android",
                appVersion = "1.4.2 (61)",
                osVersion = "14",
                deviceModel = "Xiaomi 23021RAA2Y",
                video = false,
                outgoing = true,
                startedAt = EPOCH,
                endedAt = EPOCH.plusSeconds(167),
                receivedAt = EPOCH.plusSeconds(600),
                endCause = "RECONNECT_GAVE_UP",
                summary = StaffReportSummary(162_000, 32, 90.5, 410.0, 4.2, 30.0, 1.3, 8_000, 700_000, 1),
                events = listOf(StaffReportEvent(0, "phase", "Ringing"), StaffReportEvent(61_000, "socket", "down")),
                samples = listOf(StaffReportSample(5_000, 84.0, 3.1, 0.0, 250, 21_000)),
            )
        calls[callId] =
            StaffCall(
                line,
                listOf(
                    StaffCallParticipant(caller, "LEFT", EPOCH, "RECONNECT_GAVE_UP", report),
                    StaffCallParticipant(callee, "LEFT", EPOCH.plusSeconds(5), null, null),
                ),
            )
        return callId
    }

    override suspend fun callsOf(
        userId: UserId,
        cursor: String?,
        limit: Int,
    ): Resource<StaffCallPage> {
        val mine = calls.values.filter { call -> call.participants.any { it.userId == userId } }.map { it.line }
        return Resource.success(StaffCallPage(mine.sortedByDescending { it.callId.value }.take(limit), null))
    }

    override suspend fun call(callId: CallId): StaffCall? = calls[callId]
}
