package com.circaso.calls

import com.circaso.calls.api.CallEnded
import com.circaso.calls.api.CallId
import com.circaso.calls.domain.errors.CallsError
import com.circaso.calls.domain.models.Call
import com.circaso.calls.domain.models.CallKind
import com.circaso.calls.domain.models.CallState
import com.circaso.calls.domain.models.CallView
import com.circaso.calls.domain.models.EndReason
import com.circaso.calls.domain.models.JoinCommand
import com.circaso.calls.domain.models.Joined
import com.circaso.calls.domain.models.MediaCommand
import com.circaso.calls.domain.models.ParticipantState
import com.circaso.calls.domain.models.PullCommand
import com.circaso.calls.domain.models.Pulled
import com.circaso.calls.domain.models.RenegotiateCommand
import com.circaso.calls.domain.models.TrackOffer
import com.circaso.calls.domain.models.TrackWanted
import com.circaso.calls.domain.ports.CallFrame
import com.circaso.calls.domain.ports.CallsOutbox
import com.circaso.calls.domain.ports.FrameSender
import com.circaso.calls.domain.ports.Transactor
import com.circaso.calls.domain.repositories.CallRepository
import com.circaso.calls.domain.support.CallChanges
import com.circaso.calls.domain.support.CallNotices
import com.circaso.calls.domain.support.CallViews
import com.circaso.calls.domain.support.People
import com.circaso.calls.domain.support.Sessions
import com.circaso.calls.domain.usecases.DeclineCall
import com.circaso.calls.domain.usecases.ExpireRings
import com.circaso.calls.domain.usecases.GetCall
import com.circaso.calls.domain.usecases.GetLiveCall
import com.circaso.calls.domain.usecases.JoinCall
import com.circaso.calls.domain.usecases.LeaveCall
import com.circaso.calls.domain.usecases.PullTracks
import com.circaso.calls.domain.usecases.Renegotiate
import com.circaso.calls.domain.usecases.SetMedia
import com.circaso.calls.domain.usecases.StartCall
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
import com.circaso.shared.DomainError
import com.circaso.shared.DomainEvent
import com.circaso.shared.EventBus
import com.circaso.shared.EventEnvelope
import com.circaso.shared.EventId
import com.circaso.shared.EventSubscription
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.api.Profiles
import com.circaso.users.api.PublicProfile
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
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
        val pulled = tracks.map { PulledTrack(it.trackName, it.sessionId, "${++mids}") }
        return Resource.success(PulledTracks("v=0 offer for $sessionId", pulled.isNotEmpty(), pulled))
    }

    override suspend fun renegotiate(sessionId: String, answerSdp: String): Resource<Unit> {
        renegotiated += sessionId
        return Resource.success(Unit)
    }

    override suspend fun closeTracks(sessionId: String, mids: List<String>): Resource<Unit> {
        closed += sessionId to mids
        return Resource.success(Unit)
    }

    override suspend fun iceServers() = listOf(IceServer(listOf("stun:stun.cloudflare.com:3478")))
}

private class Harness {
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
    val notices = CallNotices(frames, views, media, bus)
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
    val expire = ExpireRings(calls, changes, clock)
    val ringAgain =
        com.circaso.calls.domain.usecases
            .RingAgain(changes, views, people, frames, clock)

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
            assertEquals(CallsError.AlreadyInCall, failure(h.start(h.otieno, h.direct, false)))
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
    fun `the call ends when fewer than two are left, and a member can rejoin a group call meanwhile`() =
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
            val ended = success(h.leave(h.achieng, call.callId))

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
            assertEquals(EndReason.COMPLETED, h.stored(call).endReason)
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
}
