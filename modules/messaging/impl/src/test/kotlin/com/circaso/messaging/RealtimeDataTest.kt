package com.circaso.messaging

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.data.cache.RealtimeFramesImpl
import com.circaso.messaging.data.cache.RedisMembersCache
import com.circaso.messaging.data.entities.MessagingRealtimeRow
import com.circaso.messaging.data.migrations.V001_CreateMessagingTables
import com.circaso.messaging.domain.usecases.ReplayFrames
import com.circaso.messaging.presentation.api.MessagingRealtimeReplay
import com.circaso.messaging.presentation.services.RealtimeService
import com.circaso.shared.Realtime
import com.circaso.shared.RealtimeEnvelope
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import io.kandra.migrate.KandraMigrationRunner
import io.kandra.runtime.repository.KandraSuspendRepository
import io.kandra.test.KandraRuntimeHandle
import io.kandra.test.KandraTestBackend
import io.kandra.test.KandraTestUtils
import io.kandra.test.KandraTestcontainers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The replayed frames against a real Cassandra-compatible container (`realtime_by_user`) and Redis in memory: a seq
 * per recipient, the row kept before delivery, list data as JSON arrays, failed deliveries dropped, the replay and
 * its resync. Also the members cache's encoding and expiries.
 */
class RealtimeDataTest {
    private val db: KandraRuntimeHandle =
        run {
            KandraTestBackend.newSession().use { admin ->
                admin.execute(KandraTestBackend.createKeyspaceCql("circaso_messaging"))
                KandraMigrationRunner(admin, keyspace = "circaso_messaging").run(V001_CreateMessagingTables)
            }
            KandraTestcontainers.freshKeyspace(MessagingRealtimeRow::class)
        }

    private val rows = db.suspendRepository<MessagingRealtimeRow>()
    private val redis = MemoryRedis()
    private val realtime = RecordingRealtime(rows)
    private val frames = RealtimeFramesImpl(redis, rows, realtime, Clock.systemUTC())
    private val replay = MessagingRealtimeReplay(RealtimeService(ReplayFrames(frames)))

    @AfterTest
    fun close() {
        db.close()
        KandraTestUtils.reset()
    }

    /** Delivers by recording; checks the row is already kept; refuses the users in [failFor]. */
    private class RecordingRealtime(private val rows: KandraSuspendRepository<MessagingRealtimeRow>) : Realtime {
        val delivered = CopyOnWriteArrayList<Pair<UserId, RealtimeEnvelope>>()
        val keptFirst = CopyOnWriteArrayList<Boolean>()
        val failFor = mutableSetOf<UserId>()

        override suspend fun deliver(userId: UserId, envelope: RealtimeEnvelope): Resource<Unit> {
            keptFirst += rows.findById(userId.value, envelope.seq) != null
            if (userId in failFor) error("node unreachable")
            delivered += userId to envelope
            return Resource.success(Unit)
        }

        override suspend fun deliverMany(userIds: Collection<UserId>, envelope: RealtimeEnvelope) =
            error("replayed frames go per recipient")
    }

    @Test
    fun `each recipient gets their own seq, and the row is kept before delivery`() =
        runBlocking<Unit> {
            val (amina, brian) = List(2) { UserId.new() }

            frames.send(listOf(amina, brian), "member.added", mapOf("chatId" to "c1"))
            frames.send(listOf(amina), "chat.updated", mapOf("chatId" to "c1", "version" to 3L))
            frames.send(listOf(amina, brian), "member.removed", mapOf("chatId" to "c1"))

            val seqs = realtime.delivered.groupBy({ it.first }, { it.second.seq })
            assertEquals(listOf(1L, 2L, 3L), seqs[amina])
            assertEquals(listOf(1L, 2L), seqs[brian])
            assertTrue(realtime.keptFirst.all { it }, "every frame was in realtime_by_user before it went out")
            assertEquals(Duration.ofDays(8), redis.ttls["seq:$amina"])
            val updated = realtime.delivered.single { it.second.type == "chat.updated" }.second
            assertEquals(3L, (updated.data.jsonObject["version"] as JsonPrimitive).long, "numbers stay numbers")
        }

    @Test
    fun `a list in the data goes out as a JSON array`() =
        runBlocking<Unit> {
            val amina = UserId.new()

            frames.send(listOf(amina), "member.added", mapOf("userIds" to listOf("u1", "u2")))

            val userIds =
                realtime.delivered
                    .single()
                    .second.data.jsonObject["userIds"]
            assertEquals(JsonArray(listOf(JsonPrimitive("u1"), JsonPrimitive("u2"))), userIds)
        }

    @Test
    fun `a failed delivery is dropped, and the frame waits for the replay`() =
        runBlocking<Unit> {
            val (amina, brian) = List(2) { UserId.new() }
            realtime.failFor += brian

            frames.send(listOf(amina, brian), "member.removed", mapOf("chatId" to "c1"))

            assertEquals(listOf(amina), realtime.delivered.map { it.first })
            val missed = replay.since(brian, 0).data()
            assertEquals(listOf("member.removed"), missed.map { it.type })
            assertEquals(listOf(1L), missed.map { it.seq })
        }

    @Test
    fun `the replay returns what was missed, in order, and asks for a resync past a gap`() =
        runBlocking<Unit> {
            val amina = UserId.new()
            repeat(3) { frames.send(listOf(amina), "chat.updated", mapOf("version" to it.toLong())) }

            assertEquals(listOf(2L, 3L), replay.since(amina, 1).data().map { it.seq })
            assertEquals(emptyList(), replay.since(amina, 3).data())

            rows.delete(MessagingRealtimeRow(amina.value, 1, ""))
            val resync = replay.since(amina, 0).data().single()
            assertEquals("resync", resync.type)
            assertEquals("messaging", resync.module)
        }

    @Test
    fun `the members cache keeps ids for 10 minutes and a change marker for a few seconds`() =
        runBlocking<Unit> {
            val cache = RedisMembersCache(redis)
            val chatId = ChatId.new()
            val members = List(3) { UserId.new() }.toSet()

            assertNull(cache.get(chatId))
            cache.fillIfAbsent(chatId, members)
            assertEquals(members, cache.get(chatId))
            assertEquals(Duration.ofMinutes(10), redis.ttls["members:$chatId"])

            cache.markChanged(chatId)
            assertNull(cache.get(chatId), "a marked chat is read from the rows")
            assertEquals(Duration.ofSeconds(5), redis.ttls["members:$chatId"])
            cache.fillIfAbsent(chatId, members)
            assertEquals(RedisMembersCache.CHANGED, redis.values["members:$chatId"], "no fill over the marker")
        }
}
