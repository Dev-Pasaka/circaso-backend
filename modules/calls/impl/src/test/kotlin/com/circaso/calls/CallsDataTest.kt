package com.circaso.calls

import com.circaso.calls.api.CallId
import com.circaso.calls.data.cache.CallsExpiry
import com.circaso.calls.data.repositories.CallReportRepositoryImpl
import com.circaso.calls.data.repositories.CallRepositoryImpl
import com.circaso.calls.di.callsDatabaseSpec
import com.circaso.calls.domain.models.Call
import com.circaso.calls.domain.models.CallKind
import com.circaso.calls.domain.models.CallReport
import com.circaso.calls.domain.models.CallState
import com.circaso.calls.domain.models.EndReason
import com.circaso.calls.domain.models.LeaveCause
import com.circaso.calls.domain.models.Participant
import com.circaso.calls.domain.models.ParticipantState
import com.circaso.calls.domain.models.ReportEvent
import com.circaso.calls.domain.models.ReportSample
import com.circaso.calls.domain.models.ReportSummary
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.messaging.api.ChatId
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `calls` on PostgreSQL, migrated by its own Flyway scripts: the leave cause, the reports, a person's calls, the purge. */
class CallsDataTest {
    private val database = TestPostgres.database(callsDatabaseSpec)
    private val calls = CallRepositoryImpl(database)
    private val reports = CallReportRepositoryImpl(database)
    private val amina = UserId.new()
    private val baraka = UserId.new()
    private val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    @BeforeTest
    fun clean() = TestPostgres.truncate("calls")

    private fun person(userId: UserId, cause: LeaveCause? = null) =
        Participant(
            userId = userId,
            state = ParticipantState.LEFT,
            sessionId = null,
            tracks = emptyList(),
            audio = true,
            video = false,
            joinedAt = now,
            updatedAt = now,
            leftCause = cause,
        )

    private fun call(at: Instant = now) =
        Call(
            callId = CallId(Uuid7.generate(at)),
            chatId = ChatId(Uuid7.generate()),
            kind = CallKind.VOICE,
            state = CallState.ENDED,
            startedBy = amina,
            isGroup = false,
            createdAt = at,
            startedAt = at,
            endedAt = at.plusSeconds(162),
            endReason = EndReason.DROPPED,
            activeAt = at,
            participants = listOf(person(amina, LeaveCause.RECONNECT_GAVE_UP), person(baraka)),
        )

    private fun report(
        call: Call,
        who: UserId,
        receivedAt: Instant = now,
        endCause: String = "RECONNECT_GAVE_UP",
    ) = CallReport(
        callId = call.callId,
        userId = who,
        platform = "android",
        appVersion = "1.4.2 (61)",
        osVersion = "14",
        deviceModel = "Xiaomi 23021RAA2Y",
        video = false,
        outgoing = true,
        endCause = endCause,
        startedAt = now,
        endedAt = now.plusSeconds(162),
        receivedAt = receivedAt,
        summary = ReportSummary(durationMs = 162_000, avgRttMs = 90.5, reconnects = 1),
        events = listOf(ReportEvent(0, "phase", "Ringing"), ReportEvent(61_000, "ice", "failed")),
        samples = listOf(ReportSample(5_000, 84.0, null, 0.0, 250, 21_000)),
    )

    @Test
    fun `the leave cause and DROPPED are stored and read back`() =
        runBlocking<Unit> {
            val call = call()
            calls.insert(call)

            val read = calls.find(call.callId)!!

            assertEquals(EndReason.DROPPED, read.endReason)
            assertEquals(LeaveCause.RECONNECT_GAVE_UP, read.participant(amina)?.leftCause)
            assertNull(read.participant(baraka)?.leftCause)
        }

    @Test
    fun `a report is upserted per person per call and read back whole`() =
        runBlocking<Unit> {
            val call = call()
            calls.insert(call)
            reports.upsert(report(call, amina, endCause = "STALL"))
            reports.upsert(report(call, amina))

            val read = reports.forCall(call.callId).single()

            assertEquals(report(call, amina), read)
        }

    @Test
    fun `a person's calls come newest first a page at a time, with how many people and reports each had`() =
        runBlocking<Unit> {
            val older = call(now.minusSeconds(60))
            val newer = call(now)
            calls.insert(older)
            calls.insert(newer)
            reports.upsert(report(older, amina))
            reports.upsert(report(older, baraka))

            val first = calls.ofUser(baraka, null, 1)
            val second = calls.ofUser(baraka, first.last().callId, 5)

            assertEquals(listOf(newer.callId), first.map { it.callId })
            assertEquals(listOf(older.callId), second.map { it.callId })
            assertEquals(2, second.single().participants)
            assertEquals(2, second.single().reports)
            assertEquals(0, first.single().reports)
            assertEquals(EndReason.DROPPED, second.single().endReason)
            assertEquals(emptyList(), calls.ofUser(UserId.new(), null, 5))
        }

    @Test
    fun `the purge deletes reports 30 days after they came and keeps newer ones`() =
        runBlocking<Unit> {
            val call = call()
            calls.insert(call)
            reports.upsert(report(call, amina, receivedAt = now.minus(Duration.ofDays(31))))
            reports.upsert(report(call, baraka, receivedAt = now.minus(Duration.ofDays(29))))

            CallsExpiry(database).run()

            assertEquals(listOf(baraka), reports.forCall(call.callId).map { it.userId })
        }
}
