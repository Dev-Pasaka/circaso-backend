package com.circaso.calls

import com.circaso.calls.api.CallEnded
import com.circaso.calls.api.CallId
import com.circaso.calls.domain.errors.CallsError
import com.circaso.calls.domain.models.Call
import com.circaso.calls.domain.models.CallKind
import com.circaso.calls.domain.models.CallLine
import com.circaso.calls.domain.models.CallReport
import com.circaso.calls.domain.models.CallState
import com.circaso.calls.domain.models.CallView
import com.circaso.calls.domain.models.ConnectedCommand
import com.circaso.calls.domain.models.EndReason
import com.circaso.calls.domain.models.JoinCommand
import com.circaso.calls.domain.models.Joined
import com.circaso.calls.domain.models.LeaveCause
import com.circaso.calls.domain.models.MediaCommand
import com.circaso.calls.domain.models.ParticipantState
import com.circaso.calls.domain.models.PullCommand
import com.circaso.calls.domain.models.Pulled
import com.circaso.calls.domain.models.RenegotiateCommand
import com.circaso.calls.domain.models.ReportEvent
import com.circaso.calls.domain.models.ReportSample
import com.circaso.calls.domain.models.ReportSummary
import com.circaso.calls.domain.models.TrackOffer
import com.circaso.calls.domain.models.TrackWanted
import com.circaso.calls.domain.ports.CallFrame
import com.circaso.calls.domain.ports.CallsOutbox
import com.circaso.calls.domain.ports.FrameSender
import com.circaso.calls.domain.ports.ReportAllowance
import com.circaso.calls.domain.ports.Transactor
import com.circaso.calls.domain.repositories.CallReportRepository
import com.circaso.calls.domain.repositories.CallRepository
import com.circaso.calls.domain.support.CallChanges
import com.circaso.calls.domain.support.CallNotices
import com.circaso.calls.domain.support.CallViews
import com.circaso.calls.domain.support.People
import com.circaso.calls.domain.support.Sessions
import com.circaso.calls.domain.usecases.DeclineCall
import com.circaso.calls.domain.usecases.ExpireRings
import com.circaso.calls.domain.usecases.GetCall
import com.circaso.calls.domain.usecases.GetCallRecord
import com.circaso.calls.domain.usecases.GetLiveCall
import com.circaso.calls.domain.usecases.JoinCall
import com.circaso.calls.domain.usecases.LeaveCall
import com.circaso.calls.domain.usecases.ListCallsOfUser
import com.circaso.calls.domain.usecases.PullTracks
import com.circaso.calls.domain.usecases.Renegotiate
import com.circaso.calls.domain.usecases.ReportConnected
import com.circaso.calls.domain.usecases.SendCallReport
import com.circaso.calls.domain.usecases.SetMedia
import com.circaso.calls.domain.usecases.StartCall
import com.circaso.calls.presentation.api.CallsAdminImpl
import com.circaso.calls.presentation.controllers.callController
import com.circaso.calls.presentation.mappers.leaveCauseOf
import com.circaso.calls.presentation.services.CallAdminService
import com.circaso.calls.presentation.services.CallService
import com.circaso.integrations.api.CallMedia
import com.circaso.integrations.api.IceServer
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.LocalTrack
import com.circaso.integrations.api.PulledTrack
import com.circaso.integrations.api.PulledTracks
import com.circaso.integrations.api.PushedTracks
import com.circaso.integrations.api.RemoteTrack
import com.circaso.messaging.api.ChatDirectory
import com.circaso.messaging.api.ChatId
import com.circaso.shared.DeviceId
import com.circaso.shared.DomainError
import com.circaso.shared.DomainEvent
import com.circaso.shared.EventBus
import com.circaso.shared.EventEnvelope
import com.circaso.shared.EventId
import com.circaso.shared.EventSubscription
import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.UserId
import com.circaso.shared.getOrNull
import com.circaso.users.api.Profiles
import com.circaso.users.api.PublicProfile
import com.circaso.web.CallPrincipal
import com.circaso.web.CallPrincipalKey
import com.circaso.web.installJson
import com.circaso.web.installStatusPages
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemoryCalls : CallRepository {
    val rows = mutableMapOf<CallId, Call>()

    override suspend fun insert(call: Call): Boolean {
        if (rows.values.any { it.chatId == call.chatId && !it.isEnded }) return false
        rows[call.callId] = call
        return true
    }

    override suspend fun find(callId: CallId) = rows[callId]

    override suspend fun lock(callId: CallId) = rows[callId]

    override suspend fun live(chatId: ChatId) = rows.values.firstOrNull { it.chatId == chatId && !it.isEnded }

    override suspend fun save(before: Call, after: Call) {
        rows[after.callId] = after
    }

    override suspend fun touch(callId: CallId, at: Instant) {
        rows[callId]?.let { rows[callId] = it.copy(activeAt = at) }
    }

    override suspend fun ringingBefore(cutoff: Instant) =
        rows.values
            .filter { !it.isEnded && it.createdAt < cutoff }
            .filter { call -> call.participants.any { it.state == ParticipantState.INVITED } }
            .map { it.callId }

    override suspend fun idleSince(cutoff: Instant) =
        rows.values.filter { it.state == CallState.ACTIVE && it.activeAt < cutoff }.map { it.callId }

    var reports: MemoryReports? = null

    override suspend fun ofUser(
        userId: UserId,
        before: CallId?,
        limit: Int,
    ) = rows.values
        .filter { call -> call.participant(userId) != null && (before == null || call.callId.value < before.value) }
        .sortedByDescending { it.callId.value }
        .take(limit)
        .map { call ->
            CallLine(
                call.callId,
                call.chatId,
                call.kind,
                call.state,
                call.endReason,
                call.isGroup,
                call.startedBy,
                call.createdAt,
                call.startedAt,
                call.endedAt,
                call.participants.size,
                reports?.rows?.keys?.count { it.first == call.callId } ?: 0,
            )
        }
}

private class MemoryReports : CallReportRepository {
    val rows = linkedMapOf<Pair<CallId, UserId>, CallReport>()

    override suspend fun upsert(report: CallReport) {
        rows[report.callId to report.userId] = report
    }

    override suspend fun forCall(callId: CallId) = rows.values.filter { it.callId == callId }
}

/** The hourly report cap as a plain counter; [retryAfter] is what a refused report is told. */
private class MemoryAllowance(private val retryAfter: Long = 1_800) : ReportAllowance {
    val taken = mutableMapOf<UserId, Long>()

    override suspend fun take(
        userId: UserId,
        max: Long,
        window: Duration,
    ): Long? {
        val count = taken.merge(userId, 1, Long::plus)!!
        return if (count > max) retryAfter else null
    }
}

/** Runs the block as if in a transaction: an error leaves the rows as they were. */
private class MemoryTransactor(private val calls: MemoryCalls) : Transactor {
    override suspend fun <T> attempt(operation: String, block: suspend () -> Resource<T>): Resource<T> {
        val saved = calls.rows.toMap()
        val result = block()
        if (result is Resource.Error) {
            calls.rows.clear()
            calls.rows.putAll(saved)
        }
        return result
    }
}

private class FakeChats : ChatDirectory {
    val members = mutableMapOf<ChatId, Set<UserId>>()
    val groups = mutableSetOf<ChatId>()

    override suspend fun isMember(chatId: ChatId, userId: UserId) = userId in members[chatId].orEmpty()

    override suspend fun members(chatId: ChatId) = members[chatId].orEmpty()

    override suspend fun admins(chatId: ChatId) = emptySet<UserId>()

    override suspend fun isGroup(chatId: ChatId) = chatId in groups

    override suspend fun groupsOf(userId: UserId) = groups.filter { userId in members[it].orEmpty() }

    override suspend fun owner(chatId: ChatId): UserId? = null
}

private class FakeProfiles : Profiles {
    val names = mutableMapOf<UserId, String>()

    override suspend fun get(ids: Set<UserId>) =
        ids.mapNotNull { id -> names[id]?.let { id to PublicProfile(id, it, null, null, null) } }.toMap()
}

private class RecordingFrames : FrameSender {
    val sent = mutableListOf<Pair<Set<UserId>, CallFrame>>()

    override suspend fun send(userIds: Collection<UserId>, frame: CallFrame) {
        sent += userIds.toSet() to frame
    }

    inline fun <reified T : CallFrame> ofType() = sent.filter { it.second is T }
}

private class MemoryBus : EventBus {
    val published = mutableListOf<EventEnvelope>()

    override fun <E : DomainEvent> envelope(event: E, eventSerializer: KSerializer<E>): EventEnvelope =
        EventEnvelope(
            EventId.new(),
            event.type,
            1,
            Instant.EPOCH,
            "calls",
            null,
            Json.encodeToJsonElement(eventSerializer, event),
        )

    override suspend fun publish(envelope: EventEnvelope) {
        published += envelope
    }

    override fun subscribe(subscription: EventSubscription) = Unit
}

private class RecordingOutbox : CallsOutbox {
    val written = mutableListOf<EventEnvelope>()

    override suspend fun write(events: List<EventEnvelope>) {
        written += events
    }
}

/** The SFU: made-up sessions and SDP, remembering what each session publishes; fails on demand. */
private class TestMedia : CallMedia {
    var down = false

    /** How long the SFU takes to answer a pull and a close (virtual time under runTest). */
    var pullMillis = 0L
    var closeMillis = 0L

    /** What the next closes answer, in order; then success. */
    val closeAnswers = ArrayDeque<Resource<Unit>>()
    var closeAttempts = 0
    val published = mutableMapOf<String, Set<String>>()
    val closed = mutableListOf<Pair<String, List<String>>>()
    val renegotiated = mutableListOf<String>()
    private var sessions = 0
    private var mids = 100

    override suspend fun newSession(): Resource<String> = Resource.success("session-${++sessions}")

    override suspend fun pushTracks(
        sessionId: String,
        offerSdp: String,
        tracks: List<LocalTrack>,
    ): Resource<PushedTracks> {
        if (down) return Resource.error(IntegrationError.Unavailable("SFU"))
        published[sessionId] = tracks.map { it.trackName }.toSet()
        return Resource.success(PushedTracks("v=0 answer for $sessionId"))
    }

    override suspend fun pullTracks(sessionId: String, tracks: List<RemoteTrack>): Resource<PulledTracks> {
        if (pullMillis > 0) delay(pullMillis)
        val pulled = tracks.map { PulledTrack(it.trackName, it.sessionId, "${++mids}") }
        return Resource.success(PulledTracks("v=0 offer for $sessionId", pulled.isNotEmpty(), pulled))
    }

    override suspend fun renegotiate(sessionId: String, answerSdp: String): Resource<Unit> {
        renegotiated += sessionId
        return Resource.success(Unit)
    }

    override suspend fun closeTracks(sessionId: String, mids: List<String>): Resource<Unit> {
        closeAttempts++
        if (closeMillis > 0) delay(closeMillis)
        closeAnswers.removeFirstOrNull()?.let { if (it is Resource.Error) return it }
        closed += sessionId to mids
        return Resource.success(Unit)
    }

    override suspend fun iceServers() = listOf(IceServer(listOf("stun:stun.cloudflare.com:3478")))
}

/**
 * [background] runs the SFU clean-up after a change has answered: eagerly by default, so a test sees it done when the
 * call returns; a test's `backgroundScope` to let it take (virtual) time.
 */
private class Harness(background: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)) {
    val calls = MemoryCalls()
    val chats = FakeChats()
    val profiles = FakeProfiles()
    val frames = RecordingFrames()
    val bus = MemoryBus()
    val outbox = RecordingOutbox()
    val media = TestMedia()
    var now: Instant = Instant.parse("2026-10-29T10:00:00Z")
    val clock =
        object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: ZoneId?) = this

            override fun instant() = now
        }
    val people = People(chats, profiles)
    val views = CallViews(people, media)
    val pauses = mutableListOf<Long>()
    val notices =
        CallNotices(frames, views, media, bus, background) {
            pauses += it
            delay(it)
        }
    val changes = CallChanges(calls, MemoryTransactor(calls), outbox, bus, notices)
    val sessions = Sessions(calls)
    val start = StartCall(people, calls, views, frames, clock)
    val get = GetCall(people, calls, views)
    val live = GetLiveCall(people, calls, views)
    val join = JoinCall(people, calls, media, changes, notices, views, clock)
    val pull = PullTracks(sessions, calls, media, clock)
    val renegotiate = Renegotiate(sessions, calls, media, clock)
    val decline = DeclineCall(changes, views, clock)
    val leave = LeaveCall(changes, views, clock)
    val setMedia = SetMedia(changes, clock)
    val connected = ReportConnected(changes, clock)
    val expire = ExpireRings(calls, changes, clock)
    val ringAgain =
        com.circaso.calls.domain.usecases
            .RingAgain(changes, views, people, frames, clock)
    val reports = MemoryReports().also { calls.reports = it }
    val allowance = MemoryAllowance()
    val sendReport = SendCallReport(calls, reports, allowance, clock)
    val listCalls = ListCallsOfUser(calls)
    val record = GetCallRecord(calls, reports)
    val service =
        CallService(
            start,
            get,
            live,
            join,
            pull,
            renegotiate,
            decline,
            ringAgain,
            leave,
            setMedia,
            connected,
            sendReport,
        )
    val admin = CallsAdminImpl(CallAdminService(listCalls, record))

    fun report(
        call: Call,
        who: UserId,
        endCause: String = "RECONNECT_GAVE_UP",
    ) = CallReport(
        callId = call.callId,
        userId = who,
        platform = "android",
        appVersion = "1.4.2 (61)",
        osVersion = "14",
        deviceModel = "Xiaomi 23021RAA2Y",
        video = false,
        outgoing = who == call.startedBy,
        endCause = endCause,
        startedAt = call.createdAt,
        endedAt = call.createdAt.plusSeconds(162),
        receivedAt = Instant.EPOCH,
        summary = ReportSummary(durationMs = 162_000, reconnects = 1),
        events = listOf(ReportEvent(0, "phase", "Ringing"), ReportEvent(61_000, "socket", "down")),
        samples = listOf(ReportSample(5_000, 84.0, 3.1, 0.0, 250, 21_000)),
    )

    val direct = ChatId.new()
    val group = ChatId.new()
    val wanjiru = UserId.new()
    val otieno = UserId.new()
    val achieng = UserId.new()

    init {
        chats.members[direct] = setOf(wanjiru, otieno)
        chats.members[group] = setOf(wanjiru, otieno, achieng)
        chats.groups += group
        profiles.names[wanjiru] = "Wanjiru"
        profiles.names[otieno] = "Otieno"
        profiles.names[achieng] = "Achieng"
    }

    suspend fun call(
        chat: ChatId = direct,
        by: UserId = wanjiru,
        video: Boolean = false,
    ): Call = success(start(by, chat, video)).call

    suspend fun joined(call: Call, who: UserId): Joined =
        success(
            join(
                JoinCommand(
                    who,
                    call.callId,
                    "v=0 offer",
                    listOf(TrackOffer("0", "audio-$who", "audio"), TrackOffer("1", "video-$who", "video")),
                ),
            ),
        )

    fun stored(call: Call) = calls.rows.getValue(call.callId)

    fun state(call: Call, who: UserId) = stored(call).participant(who)?.state
}

private fun <T> success(result: Resource<T>): T = assertIs<Resource.Success<T>>(result).data

private fun failure(result: Resource<*>): DomainError = assertIs<Resource.Error>(result).error

class CallsTest {
    @Test
    fun `starting a call rings everyone else in the chat and hands the caller ICE servers`() =
        runTest {
            val h = Harness()
            val view = success(h.start(h.wanjiru, h.group, true))

            assertEquals(CallState.RINGING, view.call.state)
            assertEquals(CallKind.VIDEO, view.call.kind)
            assertEquals(
                setOf(h.wanjiru, h.otieno, h.achieng),
                view.call.participants
                    .map { it.userId }
                    .toSet(),
            )
            assertTrue(view.call.participants.all { it.state == ParticipantState.INVITED })
            assertEquals(
                "stun:stun.cloudflare.com:3478",
                view.iceServers
                    .single()
                    .urls
                    .single(),
            )
            val (to, frame) = h.frames.ofType<CallFrame.Ringing>().single()
            assertEquals(setOf(h.otieno, h.achieng), to, "the caller isn't rung")
            assertEquals("Wanjiru", (frame as CallFrame.Ringing).callerName)
        }

    @Test
    fun `a chat has one call at a time, and only its members can call`() =
        runTest {
            val h = Harness()
            val first = h.call()
            assertEquals(first.callId, success(h.start(h.otieno, h.direct, false)).call.callId, "that call, to join")
            assertEquals(first.callId, success(h.live(h.otieno, h.direct)).call.callId)

            assertEquals(CallsError.NotAMember, failure(h.start(h.achieng, h.direct, false)))
            assertEquals(CallsError.NotAMember, failure(h.get(h.achieng, first.callId)))
            assertEquals(CallsError.NotAMember, failure(h.live(h.achieng, h.direct)))
            assertEquals(CallsError.NotFound, failure(h.live(h.wanjiru, h.group)))
        }

    @Test
    fun `the second person to join makes the call active and everyone hears it`() =
        runTest {
            val h = Harness()
            val call = h.call()

            val mine = h.joined(call, h.wanjiru)
            assertEquals(CallState.RINGING, mine.view.call.state)
            assertTrue(mine.answerSdp.contains(mine.sessionId), "the SFU's answer for this session")

            h.now = h.now.plusSeconds(5)
            val theirs = h.joined(call, h.otieno)

            assertEquals(CallState.ACTIVE, theirs.view.call.state)
            assertEquals(h.now, theirs.view.call.startedAt)
            val me = theirs.view.call.participant(h.otieno)!!
            assertEquals(ParticipantState.JOINED, me.state)
            assertEquals(listOf("audio-${h.otieno}", "video-${h.otieno}"), me.tracks.map { it.trackName })
            assertTrue(me.audio && !me.video, "a voice call: the video track is there, the camera is not on")
            val updates = h.frames.ofType<CallFrame.Updated>()
            assertEquals(2, updates.size)
            assertEquals(setOf(h.wanjiru, h.otieno), updates.last().first)
        }

    @Test
    fun `pulling names people and the server finds their sessions`() =
        runTest {
            val h = Harness()
            val call = h.call()
            val mine = h.joined(call, h.wanjiru)
            val theirs = h.joined(call, h.otieno)

            val pulled: Pulled =
                success(
                    h.pull(
                        PullCommand(
                            h.wanjiru,
                            call.callId,
                            mine.sessionId,
                            listOf(
                                TrackWanted(h.otieno, "audio-${h.otieno}"),
                                TrackWanted(h.otieno, "screen-${h.otieno}"), // not published
                                TrackWanted(h.achieng, "audio-${h.achieng}"), // not in the call
                            ),
                        ),
                    ),
                )

            assertTrue(pulled.requiresRenegotiation)
            assertNotNull(pulled.offerSdp)
            assertEquals(h.otieno, pulled.tracks.single().userId)
            assertEquals("audio-${h.otieno}", pulled.tracks.single().trackName)
            val answered =
                h.renegotiate(RenegotiateCommand(h.wanjiru, call.callId, mine.sessionId, "v=0 answer"))
            assertIs<Resource.Success<Unit>>(answered)
            assertEquals(listOf(mine.sessionId), h.media.renegotiated)

            val wrong = PullCommand(h.wanjiru, call.callId, theirs.sessionId, emptyList())
            assertEquals(CallsError.SessionMismatch, failure(h.pull(wrong)))
            val notIn = PullCommand(h.achieng, call.callId, "s", emptyList())
            assertEquals(CallsError.NotFound, failure(h.pull(notIn)), "achieng isn't in the direct chat's call")
        }

    @Test
    fun `nothing to pull asks the SFU for nothing`() =
        runTest {
            val h = Harness()
            val call = h.call()
            val mine = h.joined(call, h.wanjiru)

            val pulled = success(h.pull(PullCommand(h.wanjiru, call.callId, mine.sessionId, emptyList())))

            assertNull(pulled.offerSdp)
            assertEquals(false, pulled.requiresRenegotiation)
        }

    @Test
    fun `declining a direct call ends it as declined`() =
        runTest {
            val h = Harness()
            val call = h.call()
            h.joined(call, h.wanjiru)

            val view = success(h.decline(h.otieno, call.callId))

            assertEquals(CallState.ENDED, view.call.state)
            assertEquals(EndReason.DECLINED, view.call.endReason)
            assertEquals(ParticipantState.DECLINED, h.state(call, h.otieno))
            assertEquals(ParticipantState.LEFT, h.state(call, h.wanjiru))
            assertEquals(
                setOf(h.wanjiru, h.otieno),
                h.frames
                    .ofType<CallFrame.Ended>()
                    .single()
                    .first,
            )
            assertEquals(1, h.media.closed.size, "the caller's tracks are closed at the SFU")
            assertEquals(
                CallEnded.TYPE,
                h.bus.published
                    .single()
                    .type,
            )
            assertEquals(h.bus.published, h.outbox.written, "the event's outbox row was written with the change")
            assertEquals(CallsError.CallEnded, failure(h.decline(h.otieno, call.callId)))
        }

    @Test
    fun `declining in a group leaves the call ringing the others`() =
        runTest {
            val h = Harness()
            val call = h.call(h.group)
            h.joined(call, h.wanjiru)

            val view = success(h.decline(h.otieno, call.callId))

            assertEquals(CallState.RINGING, view.call.state)
            assertEquals(ParticipantState.DECLINED, h.state(call, h.otieno))
            assertEquals(ParticipantState.INVITED, h.state(call, h.achieng))

            val last = success(h.decline(h.achieng, call.callId))
            assertEquals(EndReason.DECLINED, last.call.endReason, "nobody is left to ring")
        }

    @Test
    fun `a group call goes on while anyone is in it and ends when the last person leaves`() =
        runTest {
            val h = Harness()
            val call = h.call(h.group)
            h.joined(call, h.wanjiru)
            h.joined(call, h.otieno)
            h.joined(call, h.achieng)

            val oneLeft = success(h.leave(h.otieno, call.callId))
            assertEquals(CallState.ACTIVE, oneLeft.call.state, "two are still talking")
            assertEquals(ParticipantState.LEFT, h.state(call, h.otieno))
            assertNull(h.stored(call).participant(h.otieno)!!.sessionId, "the session is dropped")
            assertEquals(1, h.media.closed.size)

            h.joined(call, h.otieno)
            success(h.leave(h.otieno, call.callId))
            val alone = success(h.leave(h.achieng, call.callId))
            assertEquals(CallState.ACTIVE, alone.call.state, "one person is still on the call")
            assertEquals(0, h.bus.published.size)

            val ended = success(h.leave(h.wanjiru, call.callId))

            assertEquals(CallState.ENDED, ended.call.state)
            assertEquals(EndReason.COMPLETED, ended.call.endReason)
            assertEquals(ParticipantState.LEFT, h.state(call, h.wanjiru))
            assertEquals(1, h.frames.ofType<CallFrame.Ended>().size)
            assertEquals(1, h.bus.published.size, "ended once")
            assertEquals(CallState.ENDED, success(h.leave(h.wanjiru, call.callId)).call.state, "leaving again")
            assertEquals(1, h.bus.published.size)
        }

    @Test
    fun `the caller hanging up before anyone answered cancels the call`() =
        runTest {
            val h = Harness()
            val call = h.call(h.group)
            h.joined(call, h.wanjiru)

            val view = success(h.leave(h.wanjiru, call.callId))

            assertEquals(EndReason.CANCELLED, view.call.endReason)
            assertEquals(ParticipantState.MISSED, h.state(call, h.otieno))
            assertEquals(ParticipantState.MISSED, h.state(call, h.achieng))
            assertNull(success(h.start(h.otieno, h.group, false)).call.endReason, "the chat can call again")
        }

    @Test
    fun `a ring nobody answers in 45 seconds is missed`() =
        runTest {
            val h = Harness()
            val call = h.call()
            h.joined(call, h.wanjiru)

            h.now = h.now.plusSeconds(30)
            assertEquals(0, h.expire())
            assertEquals(CallState.RINGING, h.stored(call).state)

            h.now = h.now.plusSeconds(20)
            assertEquals(1, h.expire())

            val ended = h.stored(call)
            assertEquals(CallState.ENDED, ended.state)
            assertEquals(EndReason.MISSED, ended.endReason)
            assertEquals(ParticipantState.MISSED, ended.participant(h.otieno)!!.state)
            assertEquals(
                setOf(h.wanjiru, h.otieno),
                h.frames
                    .ofType<CallFrame.Ended>()
                    .single()
                    .first,
            )
            assertEquals(0, h.expire(), "once")
        }

    @Test
    fun `in a group call that is going on, only the people who never answered miss it`() =
        runTest {
            val h = Harness()
            val call = h.call(h.group)
            h.joined(call, h.wanjiru)
            h.joined(call, h.otieno)

            h.now = h.now.plusSeconds(60)
            h.expire()

            assertEquals(CallState.ACTIVE, h.stored(call).state)
            assertEquals(ParticipantState.MISSED, h.state(call, h.achieng))
            h.joined(call, h.achieng)
            assertEquals(ParticipantState.JOINED, h.state(call, h.achieng), "a missed member can still join")
        }

    @Test
    fun `an active call quiet for two hours is ended`() =
        runTest {
            val h = Harness()
            val call = h.call()
            h.joined(call, h.wanjiru)
            h.joined(call, h.otieno)

            h.now = h.now.plus(Duration.ofHours(1))
            success(h.setMedia(MediaCommand(h.otieno, call.callId, audio = false, video = false)))
            h.now = h.now.plus(Duration.ofMinutes(90))
            h.expire()
            assertEquals(CallState.ACTIVE, h.stored(call).state, "the media change counted as activity")

            h.now = h.now.plus(Duration.ofMinutes(31))
            h.expire()
            assertEquals(EndReason.DROPPED, h.stored(call).endReason, "nobody hung up")
        }

    @Test
    fun `muting is shown to everyone and only someone in the call can do it`() =
        runTest {
            val h = Harness()
            val call = h.call()
            h.joined(call, h.wanjiru)

            success(h.setMedia(MediaCommand(h.wanjiru, call.callId, audio = false, video = true)))

            val me = h.stored(call).participant(h.wanjiru)!!
            assertEquals(false, me.audio)
            val frame =
                h.frames
                    .ofType<CallFrame.Updated>()
                    .last()
                    .second as CallFrame.Updated
            assertEquals(
                false,
                frame.view.call
                    .participant(h.wanjiru)!!
                    .audio,
            )
            assertTrue(frame.view.iceServers.isEmpty(), "no TURN credentials kept in the frame log")
            val notJoined = MediaCommand(h.otieno, call.callId, audio = true, video = false)
            assertEquals(CallsError.NotInCall, failure(h.setMedia(notJoined)))
        }

    @Test
    fun `a shared screen is shown to everyone and is cleared by a rejoin and by leaving`() =
        runTest {
            val h = Harness()
            val call = h.call(h.group)
            h.joined(call, h.wanjiru)
            h.joined(call, h.otieno)

            success(h.setMedia(MediaCommand(h.wanjiru, call.callId, audio = true, video = true, screen = true)))
            assertTrue(h.stored(call).participant(h.wanjiru)!!.screen)
            val frame =
                h.frames
                    .ofType<CallFrame.Updated>()
                    .last()
                    .second as CallFrame.Updated
            assertTrue(
                frame.view.call
                    .participant(h.wanjiru)!!
                    .screen,
                "the others are told it's a screen",
            )
            assertEquals(
                false,
                frame.view.call
                    .participant(h.otieno)!!
                    .screen,
            )

            success(h.setMedia(MediaCommand(h.wanjiru, call.callId, audio = true, video = true)))
            assertEquals(false, h.stored(call).participant(h.wanjiru)!!.screen, "an older app's CL8 means camera")

            success(h.setMedia(MediaCommand(h.wanjiru, call.callId, audio = true, video = true, screen = true)))
            h.joined(call, h.wanjiru)
            assertEquals(false, h.stored(call).participant(h.wanjiru)!!.screen, "a rejoin starts on the camera")

            success(h.setMedia(MediaCommand(h.wanjiru, call.callId, audio = true, video = true, screen = true)))
            success(h.leave(h.wanjiru, call.callId))
            assertEquals(false, h.stored(call).participant(h.wanjiru)!!.screen, "leaving stops the share")
        }

    @Test
    fun `a ninth person can't join, and joining an ended call is refused`() =
        runTest {
            val h = Harness()
            val big = ChatId.new()
            val people = List(9) { UserId.new() }
            h.chats.members[big] = people.toSet()
            h.chats.groups += big
            val call = h.call(big, people.first())

            people.take(8).forEach { h.joined(call, it) }
            val ninth = JoinCommand(people[8], call.callId, "v=0", listOf(TrackOffer("0", "audio", "audio")))
            assertEquals(CallsError.CallFull, failure(h.join(ninth)))
            assertEquals(8, h.stored(call).joined.size)

            people.take(7).forEach { h.leave(it, call.callId) }
            assertEquals(1, h.stored(call).joined.size, "a group call goes on with one person")
            h.leave(people[7], call.callId)
            assertEquals(CallsError.CallEnded, failure(h.join(ninth)))
        }

    @Test
    fun `the SFU being down is media unavailable and changes nothing`() =
        runTest {
            val h = Harness()
            val call = h.call()
            h.media.down = true

            val result = h.join(JoinCommand(h.otieno, call.callId, "v=0", listOf(TrackOffer("0", "a", "audio"))))

            assertEquals(CallsError.MediaUnavailable, failure(result))
            assertEquals(ParticipantState.INVITED, h.state(call, h.otieno))
        }

    @Test
    fun `joining again replaces the earlier session`() =
        runTest {
            val h = Harness()
            val call = h.call()
            val first = h.joined(call, h.wanjiru)

            val second = h.joined(call, h.wanjiru)

            assertEquals(second.sessionId, h.stored(call).participant(h.wanjiru)!!.sessionId)
            assertEquals(
                first.sessionId,
                h.media.closed
                    .single()
                    .first,
                "the old session's tracks are closed",
            )
        }

    @Test
    fun `a view names everyone`() =
        runTest {
            val h = Harness()
            val view: CallView = success(h.get(h.otieno, h.call().callId))
            assertEquals("Wanjiru", view.names[h.wanjiru])
        }

    @Test
    fun `someone on a group call rings the people who missed it again, and each ring lasts its own 45 seconds`() =
        runTest {
            val h = Harness()
            val c = h.call(chat = h.group)
            h.joined(c, h.wanjiru)
            h.joined(c, h.otieno)
            h.now = h.now.plusSeconds(46)
            h.expire()
            assertEquals(ParticipantState.MISSED, h.state(c, h.achieng))

            // Not on the call: can't ring anyone.
            assertEquals(CallsError.NotInCall, failure(h.ringAgain(h.achieng, c.callId, emptySet())))
            success(h.ringAgain(h.wanjiru, c.callId, emptySet()))
            assertEquals(ParticipantState.INVITED, h.state(c, h.achieng))

            // The call is older than 45 s, but Achieng's new ring isn't: the next sweep leaves it ringing.
            h.now = h.now.plusSeconds(20)
            h.expire()
            assertEquals(ParticipantState.INVITED, h.state(c, h.achieng))
            h.now = h.now.plusSeconds(30)
            h.expire()
            assertEquals(ParticipantState.MISSED, h.state(c, h.achieng))
        }

    @Test
    fun `a direct call has nobody else to ring`() =
        runTest {
            val h = Harness()
            val c = h.call()
            h.joined(c, h.wanjiru)
            h.joined(c, h.otieno)
            assertEquals(CallsError.NotAGroup, failure(h.ringAgain(h.wanjiru, c.callId, emptySet())))
        }

    @Test
    fun `a voice call's video track doesn't mean the camera is on`() =
        runTest {
            val h = Harness()
            val voice = h.call()
            h.joined(voice, h.wanjiru)
            assertEquals(false, h.stored(voice).participant(h.wanjiru)?.video)
            val h2 = Harness()
            val video = h2.call(video = true)
            h2.joined(video, h2.wanjiru)
            assertEquals(true, h2.stored(video).participant(h2.wanjiru)?.video)
        }

    @Test
    fun `hanging up answers at once while the SFU takes 6 seconds to close both sessions`() =
        runTest {
            val h = Harness(backgroundScope)
            val call = h.call()
            val mine = h.joined(call, h.wanjiru)
            val theirs = h.joined(call, h.otieno)
            h.media.closeMillis = 5_800 // what Cloudflare took to answer 410 on 2026-11-09

            val before = currentTime
            val view = success(h.leave(h.otieno, call.callId))
            val tookMillis = currentTime - before

            // Before 2026-11-09 the two closes ran on the request, one after the other: 11,600 ms here, 11.7 s live.
            assertEquals(0L, tookMillis, "the hang-up doesn't wait for the SFU")
            assertEquals(CallState.ENDED, view.call.state)
            assertEquals(
                setOf(h.wanjiru, h.otieno),
                h.frames
                    .ofType<CallFrame.Ended>()
                    .single()
                    .first,
            )
            assertTrue(h.media.closed.isEmpty(), "still closing")

            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(
                setOf(mine.sessionId, theirs.sessionId),
                h.media.closed
                    .map { it.first }
                    .toSet(),
            )
        }

    @Test
    fun `a close the SFU can't take now is tried again in the background, and one it refuses is not`() =
        runTest {
            val h = Harness(backgroundScope)
            val call = h.call()
            h.joined(call, h.wanjiru)
            h.joined(call, h.otieno)
            h.media.closeAnswers += Resource.error(IntegrationError.Unavailable("SFU"))
            h.media.closeAnswers += Resource.error(IntegrationError.Unavailable("SFU"))

            success(h.leave(h.otieno, call.callId))
            advanceTimeBy(60_000)
            runCurrent()

            assertEquals(4, h.media.closeAttempts, "one session closed on its first try, the other on its third")
            assertEquals(2, h.media.closed.size)

            val h2 = Harness(backgroundScope)
            val c2 = h2.call()
            h2.joined(c2, h2.wanjiru)
            h2.media.closeAnswers += Resource.error(IntegrationError.Rejected("unknown session"))
            success(h2.leave(h2.wanjiru, c2.callId))
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, h2.media.closeAttempts, "a refusal isn't retried")
        }

    @Test
    fun `in a direct call either person hanging up ends it for both`() =
        runTest {
            val h = Harness()
            val call = h.call()
            h.joined(call, h.wanjiru)
            h.joined(call, h.otieno)

            val view = success(h.leave(h.wanjiru, call.callId))

            assertEquals(CallState.ENDED, view.call.state)
            assertEquals(EndReason.COMPLETED, view.call.endReason)
            assertEquals(ParticipantState.LEFT, h.state(call, h.otieno))
            assertEquals(
                setOf(h.wanjiru, h.otieno),
                h.frames
                    .ofType<CallFrame.Ended>()
                    .single()
                    .first,
            )
        }

    @Test
    fun `a callee who answers and hangs up before the caller's own join arrives ends the direct call`() =
        runTest {
            val h = Harness()
            val call = h.call()
            h.joined(call, h.otieno) // the caller (Wanjiru) is still INVITED: her CL3 is on its way

            val view = success(h.leave(h.otieno, call.callId))

            assertEquals(CallState.ENDED, view.call.state, "it used to stay RINGING, ringing nobody")
            assertEquals(EndReason.COMPLETED, view.call.endReason)
            assertEquals(
                CallsError.CallEnded,
                failure(h.join(JoinCommand(h.wanjiru, call.callId, "v=0", listOf(TrackOffer("0", "a", "audio"))))),
            )
        }

    @Test
    fun `a callee hanging up a direct call they never answered declines it`() =
        runTest {
            val h = Harness()
            val call = h.call()
            h.joined(call, h.wanjiru)

            val view = success(h.leave(h.otieno, call.callId))

            assertEquals(EndReason.DECLINED, view.call.endReason)
        }

    @Test
    fun `two people calling each other at once end up in one call`() =
        runTest {
            val h = Harness()
            val first = success(h.start(h.wanjiru, h.direct, false))
            val second = success(h.start(h.otieno, h.direct, false))

            assertEquals(first.call.callId, second.call.callId, "Otieno's call is Wanjiru's")
            assertEquals(1, h.frames.ofType<CallFrame.Ringing>().size, "nobody is rung twice")
            h.joined(first.call, h.wanjiru)
            val joined = h.joined(second.call, h.otieno)
            assertEquals(CallState.ACTIVE, joined.view.call.state)
            assertEquals(1, h.calls.rows.size)
        }

    @Test
    fun `calling again right after a hang-up starts a new call, while the SFU is still closing the last one`() =
        runTest {
            val h = Harness(backgroundScope)
            val call = h.call()
            h.joined(call, h.wanjiru)
            h.joined(call, h.otieno)
            h.media.closeMillis = 5_800

            success(h.leave(h.otieno, call.callId))
            val again = success(h.start(h.otieno, h.direct, false))

            assertTrue(again.call.callId != call.callId, "a new call, not the one ending")
            assertEquals(CallState.RINGING, again.call.state)
            assertEquals(0L, currentTime, "no wait for the SFU")
        }

    @Test
    fun `a pull takes what the SFU takes and nothing more`() =
        runTest {
            val h = Harness()
            val call = h.call()
            val mine = h.joined(call, h.wanjiru)
            h.joined(call, h.otieno)
            h.media.pullMillis = 250

            val before = currentTime
            val pulled =
                success(
                    h.pull(
                        PullCommand(
                            h.wanjiru,
                            call.callId,
                            mine.sessionId,
                            listOf(TrackWanted(h.otieno, "audio-${h.otieno}")),
                        ),
                    ),
                )

            assertEquals(250L, currentTime - before, "no sleep, poll or retry of the server's own")
            assertEquals(1, pulled.tracks.size)
        }

    @Test
    fun `a phone says its connection is up, everyone hears it, and a rejoin starts over`() =
        runTest {
            val h = Harness()
            val call = h.call()
            val mine = h.joined(call, h.wanjiru)
            h.joined(call, h.otieno)
            assertEquals(false, h.stored(call).participant(h.wanjiru)!!.isConnected)
            val updates = h.frames.ofType<CallFrame.Updated>().size

            assertIs<Resource.Success<Unit>>(h.connected(ConnectedCommand(h.wanjiru, call.callId, mine.sessionId)))

            assertEquals(true, h.stored(call).participant(h.wanjiru)!!.isConnected)
            val update = h.frames.ofType<CallFrame.Updated>()
            assertEquals(updates + 1, update.size)
            assertEquals(
                true,
                (update.last().second as CallFrame.Updated)
                    .view.call
                    .participant(h.wanjiru)!!
                    .isConnected,
            )

            h.connected(ConnectedCommand(h.wanjiru, call.callId, mine.sessionId))
            assertEquals(updates + 1, h.frames.ofType<CallFrame.Updated>().size, "saying it twice changes nothing")

            val again = h.joined(call, h.wanjiru)
            assertEquals(false, h.stored(call).participant(h.wanjiru)!!.isConnected, "a new session connects anew")
            assertEquals(
                CallsError.SessionMismatch,
                failure(h.connected(ConnectedCommand(h.wanjiru, call.callId, mine.sessionId))),
            )
            assertIs<Resource.Success<Unit>>(h.connected(ConnectedCommand(h.wanjiru, call.callId, again.sessionId)))
            assertEquals(CallsError.NotFound, failure(h.connected(ConnectedCommand(h.achieng, call.callId, "s"))))
        }

    @Test
    fun `the caller hanging up stops the callee's ring, once`() =
        runTest {
            val h = Harness()
            val call = h.call()
            h.joined(call, h.wanjiru)

            success(h.leave(h.wanjiru, call.callId))

            val (to, frame) = h.frames.ofType<CallFrame.RingStopped>().single()
            assertEquals(setOf(h.otieno), to)
            assertEquals("CANCELLED", (frame as CallFrame.RingStopped).reason)
            success(h.leave(h.wanjiru, call.callId))
            assertEquals(1, h.frames.ofType<CallFrame.RingStopped>().size)
        }

    @Test
    fun `a ring that runs out in a group call going on stops, and declining stops nothing`() =
        runTest {
            val h = Harness()
            val call = h.call(h.group)
            h.joined(call, h.wanjiru)
            h.joined(call, h.otieno)
            h.now = h.now.plusSeconds(46)
            h.expire()

            val (to, frame) = h.frames.ofType<CallFrame.RingStopped>().single()
            assertEquals(setOf(h.achieng), to)
            assertEquals("MISSED", (frame as CallFrame.RingStopped).reason)

            val h2 = Harness()
            val direct = h2.call()
            h2.joined(direct, h2.wanjiru)
            success(h2.decline(h2.otieno, direct.callId))
            assertTrue(h2.frames.ofType<CallFrame.RingStopped>().isEmpty(), "the callee ended their own ring")
        }

    @Test
    fun `a leave because the line stalled or reconnecting gave up ends the call dropped, and keeps the cause`() =
        runTest {
            for (cause in listOf(LeaveCause.STALL, LeaveCause.RECONNECT_GAVE_UP)) {
                val h = Harness()
                val call = h.call()
                h.joined(call, h.wanjiru)
                h.joined(call, h.otieno)

                val view = success(h.leave(h.otieno, call.callId, cause))

                assertEquals(EndReason.DROPPED, view.call.endReason)
                assertEquals(cause, h.stored(call).participant(h.otieno)?.leftCause)
                assertEquals(null, h.stored(call).participant(h.wanjiru)?.leftCause, "the other phone said nothing")
                val event = h.bus.published.single()
                assertTrue(event.data.toString().contains("\"DROPPED\""), "CallEnded says DROPPED")
                assertIs<CallFrame.Ended>(
                    h.frames.sent
                        .last()
                        .second,
                )
            }
        }

    @Test
    fun `hanging up on purpose or without a cause still ends the call completed`() =
        runTest {
            for (cause in listOf(LeaveCause.USER_HANGUP, LeaveCause.REMOTE_LEFT, null)) {
                val h = Harness()
                val call = h.call()
                h.joined(call, h.wanjiru)
                h.joined(call, h.otieno)

                assertEquals(EndReason.COMPLETED, success(h.leave(h.wanjiru, call.callId, cause)).call.endReason)
                assertEquals(cause, h.stored(call).participant(h.wanjiru)?.leftCause)
            }
        }

    @Test
    fun `a stall doesn't turn a cancel or a group call going on into a drop, and a rejoin clears the cause`() =
        runTest {
            val h = Harness()
            val direct = h.call()
            h.joined(direct, h.wanjiru)
            assertEquals(
                EndReason.CANCELLED,
                success(h.leave(h.wanjiru, direct.callId, LeaveCause.STALL)).call.endReason,
            )
            assertEquals(LeaveCause.STALL, h.stored(direct).participant(h.wanjiru)?.leftCause)

            val group = h.call(h.group)
            h.joined(group, h.wanjiru)
            h.joined(group, h.otieno)
            h.joined(group, h.achieng)
            success(h.leave(h.achieng, group.callId, LeaveCause.STALL))
            assertEquals(CallState.ACTIVE, h.stored(group).state)
            assertEquals(LeaveCause.STALL, h.stored(group).participant(h.achieng)?.leftCause)
            h.joined(group, h.achieng)
            assertEquals(null, h.stored(group).participant(h.achieng)?.leftCause, "a rejoin starts over")
        }

    @Test
    fun `a cause the server doesn't know is kept as other, and a body without one is no cause`() {
        assertEquals(LeaveCause.OTHER, leaveCauseOf("""{"cause":"BATTERY_DIED"}"""))
        assertEquals(LeaveCause.STALL, leaveCauseOf("""{"cause":"stall"}"""))
        assertEquals(null, leaveCauseOf(null))
        assertEquals(null, leaveCauseOf(""))
        assertEquals(null, leaveCauseOf("{}"))
        assertEquals(null, leaveCauseOf("not json"))
        assertEquals(null, leaveCauseOf("""{"cause":42}"""))
    }

    @Test
    fun `a report is stored for someone the call rang, and a repeat replaces it`() =
        runTest {
            val h = Harness()
            val call = h.call()
            h.joined(call, h.wanjiru)
            h.joined(call, h.otieno)
            success(h.leave(h.otieno, call.callId, LeaveCause.RECONNECT_GAVE_UP))
            h.now = h.now.plus(Duration.ofHours(3))

            success(h.sendReport(h.report(call, h.otieno, "STALL")))
            success(h.sendReport(h.report(call, h.otieno)))

            val stored =
                h.reports.rows.values
                    .single()
            assertEquals("RECONNECT_GAVE_UP", stored.endCause)
            assertEquals(h.now, stored.receivedAt, "stamped when it came")
            assertEquals(listOf("Ringing", "down"), stored.events.map { it.detail })
        }

    @Test
    fun `a report from someone who was never in the call, or for no call, is not found`() =
        runTest {
            val h = Harness()
            val call = h.call()
            assertEquals(CallsError.NotFound, failure(h.sendReport(h.report(call, h.achieng))))
            val other = call.copy(callId = CallId.new())
            assertEquals(CallsError.NotFound, failure(h.sendReport(h.report(other, h.wanjiru))))
            assertTrue(h.reports.rows.isEmpty())
        }

    @Test
    fun `the 31st report in an hour is rate limited with when to try again`() =
        runTest {
            val h = Harness()
            val call = h.call()
            repeat(30) { success(h.sendReport(h.report(call, h.wanjiru))) }

            val refused = failure(h.sendReport(h.report(call, h.wanjiru)))

            assertEquals("calls.report_rate_limited", refused.code)
            assertEquals(1_800L, refused.details?.retryAfterSeconds)
        }

    @Test
    fun `staff see a person's calls newest first, and a call with each phone's cause and report`() =
        runTest {
            val h = Harness()
            val first = h.call()
            h.joined(first, h.wanjiru)
            h.joined(first, h.otieno)
            success(h.leave(h.otieno, first.callId, LeaveCause.STALL))
            success(h.sendReport(h.report(first, h.otieno)))
            Thread.sleep(2) // call ids are UUIDv7: a later millisecond sorts after
            val second = h.call(h.group)

            val page = h.admin.callsOf(h.otieno, null, 1).getOrNull()!!
            assertEquals(listOf(second.callId), page.items.map { it.callId })
            val rest = h.admin.callsOf(h.otieno, page.nextCursor, 1).getOrNull()!!
            assertEquals(listOf(first.callId), rest.items.map { it.callId })
            assertEquals("DROPPED", rest.items.single().endReason)
            assertEquals(1, rest.items.single().reports)
            assertEquals("request.malformed", (h.admin.callsOf(h.otieno, "nope", 1) as Resource.Error).error.code)

            val sheet = h.admin.call(first.callId)!!
            val otieno = sheet.participants.single { it.userId == h.otieno }
            assertEquals("STALL", otieno.leftCause)
            assertEquals("RECONNECT_GAVE_UP", otieno.report?.endCause)
            assertEquals(
                84.0,
                otieno.report
                    ?.samples
                    ?.single()
                    ?.rttMs,
            )
            assertNull(sheet.participants.single { it.userId == h.wanjiru }.report)
            assertNull(h.admin.call(CallId.new()))
        }
}

/** CL7 and CL10 through Ktor, as the app sends them, with the caller set as the gateway would. */
class CallRoutesTest {
    private val h = Harness()

    private fun ApplicationTestBuilder.serve(caller: UserId) {
        application {
            installJson()
            installStatusPages()
            intercept(ApplicationCallPipeline.Plugins) {
                call.attributes.put(
                    CallPrincipalKey,
                    CallPrincipal(caller, DeviceId.new(), java.util.UUID.randomUUID(), Scope.APP),
                )
            }
            routing { route("/v1") { callController(h.service) } }
        }
    }

    private suspend fun HttpResponse.code(): String? =
        Json
            .parseToJsonElement(bodyAsText())
            .jsonObject["code"]
            ?.jsonPrimitive
            ?.content

    private suspend fun active(): Call {
        val call = h.call()
        h.joined(call, h.wanjiru)
        h.joined(call, h.otieno)
        return call
    }

    private fun reportBody(events: Int = 2, extra: String = ""): String {
        val lines = (0 until events).joinToString(",") { """{"t":${it * 100},"type":"net","detail":"offline"}""" }
        return """{"platform":"ios","appVersion":"1.4.2 (61)","osVersion":"18.1","deviceModel":"iPhone15,2",""" +
            """"video":false,"outgoing":false,"startedAt":"2026-10-29T10:00:00Z","endedAt":"2026-10-29T10:02:42Z",""" +
            """"endCause":"REMOTE_ENDED","events":[$lines],"samples":[{"t":5000,"rttMs":84.0,"jitterMs":null}],""" +
            """"summary":{"durationMs":162000,"reconnects":0}$extra}"""
    }

    @Test
    fun `CL7 works with no body and no content type, as older apps send it`() =
        testApplication {
            val call = active()
            serve(h.otieno)

            val left = client.post("/v1/calls/${call.callId}/leave")

            assertEquals(HttpStatusCode.OK, left.status, left.bodyAsText())
            assertEquals(EndReason.COMPLETED, h.stored(call).endReason)
            assertNull(h.stored(call).participant(h.otieno)?.leftCause)
        }

    @Test
    fun `CL7 with a cause keeps it, and a stall ends the call dropped`() =
        testApplication {
            val call = active()
            serve(h.otieno)

            val left =
                client.post("/v1/calls/${call.callId}/leave") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"cause":"STALL"}""")
                }

            assertEquals(HttpStatusCode.OK, left.status)
            assertTrue(left.bodyAsText().contains("\"endReason\":\"DROPPED\""))
            assertEquals(LeaveCause.STALL, h.stored(call).participant(h.otieno)?.leftCause)
        }

    @Test
    fun `CL7 with an empty body, junk or an unknown cause still hangs up`() =
        testApplication {
            val call = active()
            serve(h.otieno)

            val left =
                client.post("/v1/calls/${call.callId}/leave") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"cause":"PHONE_ON_FIRE"}""")
                }
            assertEquals(HttpStatusCode.OK, left.status)
            assertEquals(LeaveCause.OTHER, h.stored(call).participant(h.otieno)?.leftCause)
            for (body in listOf("", "{", "x".repeat(10_000))) {
                val again =
                    client.post("/v1/calls/${call.callId}/leave") {
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                assertEquals(HttpStatusCode.OK, again.status, "body '${body.take(10)}'")
            }
        }

    @Test
    fun `CL10 stores a report and answers received`() =
        testApplication {
            val call = active()
            serve(h.otieno)

            val sent =
                client.post("/v1/calls/${call.callId}/report") {
                    contentType(ContentType.Application.Json)
                    setBody(reportBody())
                }

            assertEquals(HttpStatusCode.OK, sent.status, sent.bodyAsText())
            assertTrue(sent.bodyAsText().contains("\"received\":true"))
            val stored =
                h.reports.rows.values
                    .single()
            assertEquals("ios", stored.platform)
            assertEquals("REMOTE_ENDED", stored.endCause)
            assertEquals(null, stored.samples.single().jitterMs)
            assertEquals(2, stored.events.size)
        }

    @Test
    fun `CL10 refuses a non-participant, a missing field, too many events, a long field and a body over 64 KiB`() =
        testApplication {
            val call = active()
            serve(h.achieng)

            suspend fun send(body: String) =
                client.post("/v1/calls/${call.callId}/report") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }

            val stranger = send(reportBody())
            assertEquals(HttpStatusCode.NotFound, stranger.status)
            assertEquals("calls.not_found", stranger.code())
            assertEquals("calls.report_invalid", send(reportBody().replace(""""platform":"ios",""", "")).code())
            assertEquals("calls.report_invalid", send(reportBody(events = 401)).code())
            assertEquals("calls.report_invalid", send(reportBody().replace("iPhone15,2", "x".repeat(81))).code())
            assertEquals(
                "calls.report_invalid",
                send(reportBody().replace(""""t":100""", """"t":-1""")).code(),
            )
            val huge = send(reportBody(extra = ""","padding":"${"x".repeat(70_000)}""""))
            assertEquals(HttpStatusCode.BadRequest, huge.status)
            assertEquals("calls.report_invalid", huge.code())
            assertEquals("request.malformed", send("{not json").code())
            assertEquals("calls.report_invalid", client.post("/v1/calls/${call.callId}/report").code())
            assertTrue(h.reports.rows.isEmpty())
        }

    @Test
    fun `CL10's 31st report in an hour is 429 with Retry-After`() =
        testApplication {
            val call = active()
            serve(h.otieno)
            repeat(30) {
                val ok =
                    client.post("/v1/calls/${call.callId}/report") {
                        contentType(ContentType.Application.Json)
                        setBody(reportBody())
                    }
                assertEquals(HttpStatusCode.OK, ok.status)
            }
            val refused =
                client.post("/v1/calls/${call.callId}/report") {
                    contentType(ContentType.Application.Json)
                    setBody(reportBody())
                }
            assertEquals(HttpStatusCode.TooManyRequests, refused.status)
            assertEquals("calls.report_rate_limited", refused.code())
            assertEquals("1800", refused.headers[HttpHeaders.RetryAfter])
        }
}
