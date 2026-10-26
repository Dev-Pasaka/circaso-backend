package com.circaso.assistant

import com.circaso.assistant.data.cache.RedisQuotaCounters
import com.circaso.infrastructure.api.RedisScope
import com.circaso.shared.UserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The daily voice cap holds with several sockets metering at once (decision 2026-10-26). */
class QuotaCountersTest {
    /** Redis's counters, one atomic `INCRBY` at a time, yielding around each so concurrent callers interleave. */
    private class CounterRedis : RedisScope {
        override val prefix = "ast"
        val values = ConcurrentHashMap<String, Long>()

        override suspend fun get(key: String) = values[key]?.toString()

        override suspend fun incrementBy(
            key: String,
            by: Long,
            ttl: Duration,
        ): Long {
            yield()
            val after = values.merge(key, by, Long::plus)!!
            yield()
            return after
        }

        override suspend fun increment(key: String, ttl: Duration) = incrementBy(key, 1, ttl)

        override suspend fun getPipelined(keys: List<String>) = keys.map { get(it) }

        override suspend fun set(
            key: String,
            value: String,
            ttl: Duration,
        ) = error("not used")

        override suspend fun setIfAbsent(
            key: String,
            value: String,
            ttl: Duration,
        ) = error("not used")

        override suspend fun getAndDelete(key: String) = error("not used")

        override suspend fun delete(key: String) = error("not used")

        override suspend fun exists(key: String) = error("not used")

        override suspend fun incrementRenewing(key: String, ttl: Duration) = error("not used")

        override suspend fun hashSet(
            key: String,
            fields: Map<String, String>,
            ttl: Duration,
        ) = error("not used")

        override suspend fun hashGetAll(key: String) = error("not used")

        override suspend fun hashIncrement(
            key: String,
            field: String,
            by: Long,
        ) = error("not used")

        override suspend fun ttl(key: String) = error("not used")

        override suspend fun publish(channel: String, message: String) = error("not used")

        override fun subscribe(channel: String): Flow<String> = error("not used")
    }

    @Test
    fun `sockets metering at once never take the day's voice count past the cap`() =
        runBlocking {
            val redis = CounterRedis()
            val quota = RedisQuotaCounters(redis)
            val user = UserId.new()
            quota.addVoiceSeconds(user, "2026-10-26", 4)

            val counts =
                withContext(Dispatchers.Default) {
                    (1..60)
                        .map { i ->
                            async { quota.addVoiceSecondsUpTo(user, "2026-10-26", 1 + i % 3, 30) }
                        }.awaitAll()
                }

            assertEquals(30, quota.voiceSeconds(user, "2026-10-26"), "exactly at the cap once every socket is done")
            assertTrue(counts.all { it <= 30 }, "no socket is told a count past the cap")
        }

    @Test
    fun `under the cap every second is charged`() =
        runBlocking {
            val quota = RedisQuotaCounters(CounterRedis())
            val user = UserId.new()

            assertEquals(3, quota.addVoiceSecondsUpTo(user, "d", 3, 10))
            assertEquals(10, quota.addVoiceSecondsUpTo(user, "d", 9, 10), "only 7 of the 9 fit")
            assertEquals(10, quota.addVoiceSecondsUpTo(user, "d", 2, 10), "none fit")
        }
}
