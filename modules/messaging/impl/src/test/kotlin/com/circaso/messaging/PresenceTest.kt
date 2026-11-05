package com.circaso.messaging

import com.circaso.messaging.api.PresenceChangedFrame
import com.circaso.messaging.presentation.dto.PhoneFrame
import com.circaso.shared.CircasoJson
import com.circaso.shared.ClientFrame
import com.circaso.shared.DeviceId
import com.circaso.shared.UserId
import kotlinx.coroutines.runBlocking
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Online and last seen (`presence`, `presence.watch`, `presence.changed`) over the fakes and the Redis adapters. */
class PresenceTest {
    private val h = DeliveryHarness()
    private val clock = h.chats.clock
    private val amina = UserId.new()
    private val brian = UserId.new()
    private val chidi = UserId.new()

    private fun changesTo(userId: UserId): List<PresenceChangedFrame> =
        h.live
            .of(PresenceChangedFrame.TYPE)
            .filter { userId in it.to }
            .map { it.data<PresenceChangedFrame>() }

    @Test
    fun `a watch on a chat the caller isn't in is refused and tells them nothing`() =
        runBlocking<Unit> {
            val chat = h.direct(amina, brian)
            h.updatePresence(amina, true).data()

            assertEquals("messaging.chat_not_found", h.watchPresence(chidi, chat).errorCode())
            assertEquals("messaging.chat_not_found", h.getPresence(chidi, chat).errorCode())
            assertTrue(changesTo(chidi).isEmpty())
            assertTrue(
                h.presenceRedis.hashes.keys
                    .none { it.startsWith("presence-watchers:") },
            )
        }

    @Test
    fun `a watch answers each other member's state now, and hears active then away with the last seen`() =
        runBlocking<Unit> {
            val chat = h.direct(amina, brian)

            val now = h.watchPresence(brian, chat).data()
            assertEquals(listOf(amina), now.map { it.userId })
            val first = changesTo(brian).single()
            assertEquals(PresenceChangedFrame(amina, false, null), first)

            h.updatePresence(amina, true).data()
            val activeAt = clock.instant()
            assertEquals(PresenceChangedFrame(amina, true, activeAt), changesTo(brian).last())

            clock.advance(Duration.ofSeconds(20))
            h.updatePresence(amina, true).data()
            assertEquals(2, changesTo(brian).size, "a heartbeat while online changes nothing")

            clock.advance(Duration.ofSeconds(10))
            h.updatePresence(amina, false).data()
            val awayAt = clock.instant()
            assertEquals(PresenceChangedFrame(amina, false, awayAt), changesTo(brian).last())
            assertTrue(changesTo(amina).isEmpty(), "nobody hears their own presence")

            val read = h.getPresence(brian, chat).data().single()
            assertFalse(read.online)
            assertEquals(awayAt, read.lastSeenAt)
        }

    @Test
    fun `a heartbeat older than 75 seconds reads as offline, and the sweep tells the watchers once`() =
        runBlocking<Unit> {
            val chat = h.direct(amina, brian)
            h.updatePresence(amina, true).data()
            val activeAt = clock.instant()
            h.watchPresence(brian, chat).data()
            assertTrue(
                h
                    .getPresence(brian, chat)
                    .data()
                    .single()
                    .online,
            )

            clock.advance(Duration.ofSeconds(74))
            assertTrue(
                h
                    .getPresence(brian, chat)
                    .data()
                    .single()
                    .online,
            )
            h.watchPresence(brian, chat).data()
            assertEquals(0, h.sweepPresence(), "not due yet")

            clock.advance(Duration.ofSeconds(10))
            val read = h.getPresence(brian, chat).data().single()
            assertFalse(read.online)
            assertEquals(activeAt, read.lastSeenAt)

            val before = changesTo(brian).size
            assertEquals(1, h.sweepPresence())
            assertEquals(PresenceChangedFrame(amina, false, activeAt), changesTo(brian).last())
            assertEquals(before + 1, changesTo(brian).size)
            assertEquals(0, h.sweepPresence(), "each lapse is announced once")
        }

    @Test
    fun `the sweep leaves alone someone who kept their heartbeat up or said away`() =
        runBlocking<Unit> {
            val chat = h.direct(amina, brian)
            h.group(amina, listOf(brian, chidi))
            h.watchPresence(brian, chat).data()
            h.updatePresence(amina, true).data()
            h.updatePresence(chidi, true).data()
            h.updatePresence(chidi, false).data()

            repeat(4) {
                clock.advance(Duration.ofSeconds(30))
                h.updatePresence(amina, true).data()
            }
            assertEquals(0, h.sweepPresence())
            assertTrue(
                h
                    .getPresence(brian, chat)
                    .data()
                    .single()
                    .online,
            )
        }

    @Test
    fun `a watcher stops hearing once their watch lapses`() =
        runBlocking<Unit> {
            val chat = h.direct(amina, brian)
            h.watchPresence(brian, chat).data()
            clock.advance(Duration.ofSeconds(61))

            h.updatePresence(amina, true).data()

            assertEquals(1, changesTo(brian).size, "only the answer to the watch itself")
        }

    @Test
    fun `a member either of them blocked is left out`() =
        runBlocking<Unit> {
            val group = h.group(amina, listOf(brian, chidi))
            h.chats.reach.blocks += chidi to amina

            val seen = h.watchPresence(amina, group).data().map { it.userId }

            assertEquals(listOf(brian), seen)
            h.updatePresence(chidi, true).data()
            assertTrue(changesTo(amina).none { it.userId == chidi })
        }

    @Test
    fun `the socket's presence frames reach the use cases`() =
        runBlocking<Unit> {
            val chat = h.direct(amina, brian)
            val device = DeviceId.new()
            val watch =
                ClientFrame(
                    "messaging",
                    "presence.watch",
                    CircasoJson.encodeToJsonElement(
                        PhoneFrame.PresenceWatch.serializer(),
                        PhoneFrame.PresenceWatch(chat.toString()),
                    ),
                )
            h.inbound.receive(brian, device, watch).data()

            val active =
                ClientFrame(
                    "messaging",
                    "presence",
                    CircasoJson.encodeToJsonElement(PhoneFrame.Presence.serializer(), PhoneFrame.Presence("active")),
                )
            h.inbound.receive(amina, device, active).data()
            assertTrue(changesTo(brian).last().online)

            val odd =
                ClientFrame(
                    "messaging",
                    "presence",
                    CircasoJson.encodeToJsonElement(PhoneFrame.Presence.serializer(), PhoneFrame.Presence("busy")),
                )
            assertEquals("request.malformed", h.inbound.receive(amina, device, odd).errorCode())
        }

    @Test
    fun `presence keys are msg-prefixed and expire`() =
        runBlocking<Unit> {
            val chat = h.direct(amina, brian)
            h.watchPresence(brian, chat).data()
            h.updatePresence(amina, true).data()

            assertEquals(Duration.ofDays(30), h.presenceRedis.ttls["presence:$amina"])
            assertEquals(Duration.ofSeconds(60), h.presenceRedis.ttls["presence-watchers:$amina"])
            val due = h.presenceRedis.ttls.filterKeys { it.startsWith("presence-due:") }
            assertEquals(listOf(Duration.ofMinutes(10)), due.values.toList())
            assertNull(h.presenceRedis.values["presence:$brian"])
        }

    private fun bucket(): Long = clock.instant().toEpochMilli() / 5_000

    @Test
    fun `a sweep on time takes only the bucket that just ended, and remembers it`() =
        runBlocking<Unit> {
            h.sweepPresence()
            assertEquals(24, h.presenceRedis.taken.size, "with no mark, the whole 2-minute lookback")
            assertEquals((bucket() - 1).toString(), h.presenceRedis.values["presence-swept"])
            assertEquals(Duration.ofMinutes(10), h.presenceRedis.ttls["presence-swept"])

            h.presenceRedis.taken.clear()
            h.sweepPresence()
            assertTrue(h.presenceRedis.taken.isEmpty(), "nothing new has ended within the same 5 seconds")

            clock.advance(Duration.ofSeconds(5))
            h.sweepPresence()
            assertEquals(listOf("presence-due:${bucket() - 1}"), h.presenceRedis.taken)

            h.presenceRedis.taken.clear()
            clock.advance(Duration.ofSeconds(30))
            h.sweepPresence()
            assertEquals(((bucket() - 6) until bucket()).map { "presence-due:$it" }, h.presenceRedis.taken)

            h.presenceRedis.taken.clear()
            clock.advance(Duration.ofMinutes(10))
            h.sweepPresence()
            assertEquals(24, h.presenceRedis.taken.size, "a mark older than the lookback starts at the lookback")
        }

    @Test
    fun `a sweep after missed runs still tells the watchers, once`() =
        runBlocking<Unit> {
            val chat = h.direct(amina, brian)
            h.sweepPresence()
            h.updatePresence(amina, true).data()
            val activeAt = clock.instant()
            h.watchPresence(brian, chat).data()

            clock.advance(Duration.ofSeconds(110))
            h.watchPresence(brian, chat).data()
            val before = changesTo(brian).size
            assertEquals(1, h.sweepPresence())
            assertEquals(PresenceChangedFrame(amina, false, activeAt), changesTo(brian).last())
            assertEquals(before + 1, changesTo(brian).size)

            clock.advance(Duration.ofSeconds(5))
            assertEquals(0, h.sweepPresence(), "each lapse is announced once")
        }
}
