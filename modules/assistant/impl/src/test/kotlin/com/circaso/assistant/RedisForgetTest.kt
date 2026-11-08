package com.circaso.assistant

import com.circaso.assistant.data.cache.RedisPendingTurns
import com.circaso.assistant.data.cache.RedisQuotaCounters
import com.circaso.assistant.domain.models.TokenCounts
import com.circaso.assistant.domain.ports.PendingMessage
import com.circaso.assistant.domain.ports.PendingReadNote
import com.circaso.assistant.domain.ports.PendingTurn
import com.circaso.infrastructure.api.RedisScope
import com.circaso.shared.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A deleted account's `ast:` keys: its parked turns (which hold the conversation) and its counters. */
class RedisForgetTest {
    /** Plain values and hashes in maps; expiry is not modelled. */
    private class MapRedis : RedisScope {
        override val prefix = "ast"
        val values = ConcurrentHashMap<String, String>()
        val hashes = ConcurrentHashMap<String, MutableMap<String, String>>()

        override suspend fun get(key: String) = values[key]

        override suspend fun getPipelined(keys: List<String>) = keys.map { values[it] }

        override suspend fun set(
            key: String,
            value: String,
            ttl: Duration,
        ) {
            values[key] = value
        }

        override suspend fun setIfAbsent(
            key: String,
            value: String,
            ttl: Duration,
        ) = values.putIfAbsent(key, value) == null

        override suspend fun getAndDelete(key: String) = values.remove(key)

        override suspend fun delete(key: String) = (values.remove(key) ?: hashes.remove(key)) != null

        override suspend fun exists(key: String) = values.containsKey(key) || hashes.containsKey(key)

        override suspend fun increment(key: String, ttl: Duration) = incrementBy(key, 1, ttl)

        override suspend fun incrementBy(
            key: String,
            by: Long,
            ttl: Duration,
        ): Long = values.merge(key, by.toString()) { old, add -> (old.toLong() + add.toLong()).toString() }!!.toLong()

        override suspend fun incrementRenewing(key: String, ttl: Duration) = increment(key, ttl)

        override suspend fun hashSet(
            key: String,
            fields: Map<String, String>,
            ttl: Duration,
        ) {
            hashes.getOrPut(key) { ConcurrentHashMap() }.putAll(fields)
        }

        override suspend fun hashGetAll(key: String): Map<String, String> = hashes[key].orEmpty()

        override suspend fun hashTake(key: String): Map<String, String> = hashes.remove(key).orEmpty()

        override suspend fun hashDelete(key: String, field: String): Boolean {
            val hash = hashes[key] ?: return false
            val removed = hash.remove(field) != null
            if (hash.isEmpty()) hashes.remove(key) // Redis drops a hash with its last field
            return removed
        }

        override suspend fun hashIncrement(
            key: String,
            field: String,
            by: Long,
        ): Long? {
            val hash = hashes[key] ?: return null // HINCRBY only on a hash that exists, as the real script
            return hash.merge(field, by.toString()) { old, add -> (old.toLong() + add.toLong()).toString() }!!.toLong()
        }

        override suspend fun ttl(key: String) = error("not used")

        override suspend fun publish(channel: String, message: String) = error("not used")

        override fun subscribe(channel: String): Flow<String> = error("not used")
    }

    private fun turn(user: UserId) =
        PendingTurn(
            user,
            UUID.randomUUID(),
            null,
            "what did Mum say about the rent?",
            UUID.randomUUID(),
            "c1",
            "searchChats",
            listOf(PendingMessage.User("what did Mum say about the rent?")),
            PendingReadNote(emptyList(), emptyList(), 0),
            1,
        )

    @Test
    fun `forgetting a user removes every turn they parked and their index, and leaves others alone`() =
        runBlocking {
            val redis = MapRedis()
            val turns = RedisPendingTurns(redis)
            val me = UserId.new()
            val other = UserId.new()
            val mine = listOf(turn(me), turn(me))
            val theirs = turn(other)
            (mine + theirs).forEach { turns.save(it) }

            turns.forget(me)

            mine.forEach { assertEquals(null, turns.callIdOf(me, it.turnId)) }
            assertEquals("c1", turns.callIdOf(other, theirs.turnId))
            assertTrue(redis.values.keys.none { me.toString() in it } && redis.hashes.keys.none { me.toString() in it })
        }

    @Test
    fun `a taken turn leaves the user's index, and so does one that expired before its answer`() =
        runBlocking<Unit> {
            val redis = MapRedis()
            val turns = RedisPendingTurns(redis)
            val me = UserId.new()
            val (answered, expired, waiting) = listOf(turn(me), turn(me), turn(me))
            listOf(answered, expired, waiting).forEach { turns.save(it) }
            redis.values.remove("turn:$me:${expired.turnId}") // its 10 minutes passed

            assertEquals("c1", turns.take(me, answered.turnId)?.callId)
            assertNull(turns.take(me, expired.turnId))

            assertEquals<Set<String>?>(setOf(waiting.turnId.toString()), redis.hashes["turns:$me"]?.keys)
            assertEquals("c1", turns.take(me, waiting.turnId)?.callId)
            assertNull(redis.hashes["turns:$me"], "the last taken turn takes the index with it")
        }

    @Test
    fun `forgetting a user removes their counters for the given days only`() =
        runBlocking {
            val redis = MapRedis()
            val quota = RedisQuotaCounters(redis)
            val me = UserId.new()
            val other = UserId.new()
            for (day in listOf("2026-10-27", "2026-10-28")) {
                quota.countTextTurn(me, day, 10)
                quota.addVoiceSeconds(me, day, 5)
                quota.addTokens(me, day, 100, 20)
                quota.addModelTokens(me, day, mapOf("claude-haiku-5-5" to TokenCounts(50, 30, 20, 20)))
            }
            quota.countTextTurn(other, "2026-10-28", 10)

            quota.forget(me, listOf("2026-10-28", "2026-10-27", "2026-10-26"))

            assertTrue(redis.values.keys.none { me.toString() in it }, redis.values.keys.toString())
            assertEquals(1L, quota.textTurns(other, "2026-10-28"))
        }

    @Test
    fun `tokens by model add up per day, kind and model, and a model name may hold the separator`() =
        runBlocking {
            val redis = MapRedis()
            val quota = RedisQuotaCounters(redis)
            val me = UserId.new()
            val day = "2026-11-08"

            quota.addModelTokens(me, day, mapOf("claude-haiku-5-5" to TokenCounts(50, 30, 20, 20)))
            quota.addModelTokens(
                me,
                day,
                mapOf(
                    "claude-haiku-5-5" to TokenCounts(5, 0, 0, 2),
                    "@cf/odd|name" to TokenCounts(1, 0, 0, 1),
                    "nothing" to TokenCounts.ZERO,
                ),
            )

            assertEquals(
                mapOf("claude-haiku-5-5" to TokenCounts(55, 30, 20, 22), "@cf/odd|name" to TokenCounts(1, 0, 0, 1)),
                quota.modelTokens(me, day),
            )
            assertEquals(emptyMap(), quota.modelTokens(me, "2026-11-07"))
        }
}
