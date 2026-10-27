package com.circaso.messaging

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.ReceiptStatus
import com.circaso.messaging.api.TypingFrame
import com.circaso.messaging.data.cache.LiveFramesImpl
import com.circaso.messaging.data.cache.RedisPendingCounts
import com.circaso.messaging.data.cache.RedisReceiptBuffer
import com.circaso.messaging.data.cache.RedisTypingMarks
import com.circaso.messaging.domain.support.OrderedIds
import com.circaso.shared.Realtime
import com.circaso.shared.RealtimeEnvelope
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Messaging's Redis keys and live frames (Messaging → Data → Redis), over `MemoryRedis`: values and expiries. */
class RedisAdaptersTest {
    private val ids = OrderedIds(Clock.systemUTC())

    @Test
    fun `the pending counter counts down for 30 days, and a lost one is never re-created at -1`() =
        runBlocking<Unit> {
            val redis = MemoryRedis()
            val pending = RedisPendingCounts(redis)
            val messageId = ids.next()

            pending.start(messageId, 2)
            assertEquals(Duration.ofDays(30), redis.ttls["pending:$messageId"])
            assertEquals(1L, pending.countDown(messageId))
            redis.values.remove("pending:$messageId")

            assertNull(pending.countDown(messageId), "Redis lost it: no count, so no body is deleted")
            assertNull(redis.values["pending:$messageId"], "and nothing was written at -1")
        }

    @Test
    fun `receipts are kept 10 seconds, a batch is claimed once per 5-second window, and a read is never downgraded`() =
        runBlocking<Unit> {
            val redis = MemoryRedis()
            val buffer = RedisReceiptBuffer(redis, Duration.ofSeconds(5))
            val (messageId, brian, chidi) = Triple(ids.next(), UserId.new(), UserId.new())

            buffer.add(messageId, brian, ReceiptStatus.READ)
            buffer.add(messageId, brian, ReceiptStatus.DELIVERED)
            buffer.add(messageId, chidi, ReceiptStatus.DELIVERED)
            assertEquals(Duration.ofSeconds(10), redis.ttls["receipts:$messageId"])
            assertTrue(buffer.claimBatch(messageId))
            assertFalse(buffer.claimBatch(messageId))
            assertEquals(Duration.ofSeconds(5), redis.ttls["receipts-due:$messageId"])

            val first = buffer.takeBatch(messageId)
            assertEquals(mapOf(brian to ReceiptStatus.READ, chidi to ReceiptStatus.DELIVERED), first)
            assertNull(redis.hashes["receipts:$messageId"], "the batch took and cleared the hash")
            assertTrue(buffer.claimBatch(messageId), "a receipt after the take claims the next batch")
            buffer.add(messageId, chidi, ReceiptStatus.READ)
            assertEquals(mapOf(chidi to ReceiptStatus.READ), buffer.takeBatch(messageId), "only what is new")
        }

    @Test
    fun `a typing mark lasts 6 seconds and stopping clears it`() =
        runBlocking<Unit> {
            val redis = MemoryRedis()
            val marks = RedisTypingMarks(redis, Duration.ofSeconds(6))
            val (chatId, amina) = ChatId.new() to UserId.new()

            assertTrue(marks.start(chatId, amina))
            assertFalse(marks.start(chatId, amina))
            assertEquals(Duration.ofSeconds(6), redis.ttls["typing:$chatId:$amina"])
            marks.stop(chatId, amina)
            assertTrue(marks.start(chatId, amina))
        }

    @Test
    fun `a live frame goes once to everyone through one deliverMany, with seq 0, and a failure is swallowed`() =
        runBlocking<Unit> {
            val sent = CopyOnWriteArrayList<Pair<Collection<UserId>, RealtimeEnvelope>>()
            var fail = false
            val realtime =
                object : Realtime {
                    override suspend fun deliver(userId: UserId, envelope: RealtimeEnvelope) =
                        error("live frames go through deliverMany")

                    override suspend fun deliverMany(
                        userIds: Collection<UserId>,
                        envelope: RealtimeEnvelope,
                    ): Resource<Unit> {
                        if (fail) error("redis down")
                        sent += userIds to envelope
                        return Resource.success(Unit)
                    }
                }
            val live = LiveFramesImpl(realtime, Clock.systemUTC())
            val users = List(3) { UserId.new() }
            val chatId = ChatId.new()

            live.send(users, TypingFrame(chatId, users[0], true))
            fail = true
            live.send(users, TypingFrame(chatId, users[0], false))

            val (to, envelope) = sent.single()
            assertEquals(users, to.toList())
            assertEquals(0L, envelope.seq)
            assertEquals("messaging", envelope.module)
            assertEquals("typing", envelope.type)
        }
}
