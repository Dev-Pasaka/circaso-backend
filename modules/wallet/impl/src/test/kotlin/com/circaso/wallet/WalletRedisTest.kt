package com.circaso.wallet

import com.circaso.infrastructure.api.RedisScope
import com.circaso.shared.UserId
import com.circaso.wallet.data.cache.RedisAddingChains
import com.circaso.wallet.data.cache.RedisBalanceCache
import com.circaso.wallet.domain.models.Holding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import java.math.BigInteger
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Wallet's Redis keys (Wallet → Data → Redis): what they hold and for how long. */
class WalletRedisTest {
    private val redis = MemoryRedis()

    @Test
    fun `wal balance holds a chain's holdings for 15 seconds, amounts past a Long included`() =
        runBlocking<Unit> {
            val cache = RedisBalanceCache(redis)
            val holdings =
                listOf(
                    Holding("USDC", "0x1", 6, usdPegged = true, amountMinor = BigInteger.valueOf(20_500_000)),
                    Holding("ETH", "", 18, usdPegged = false, amountMinor = BigInteger("12000000000000000001")),
                )

            cache.put("w-1", holdings)

            assertEquals(holdings, cache.get("w-1"))
            assertEquals(Duration.ofSeconds(15), redis.ttls["balance:w-1"])
            cache.clear("w-1")
            assertNull(cache.get("w-1"))
        }

    @Test
    fun `wal adding lists the chains being added for an hour`() =
        runBlocking<Unit> {
            val adding = RedisAddingChains(redis)
            val amina = UserId.new()

            adding.add(amina, "SOL-DEVNET")
            adding.add(amina, "MATIC-AMOY")
            adding.remove(amina, "MATIC-AMOY")

            assertEquals(setOf("SOL-DEVNET"), adding.list(amina))
            assertEquals(Duration.ofHours(1), redis.ttls["adding:$amina"])
            assertTrue(adding.list(UserId.new()).isEmpty())
            assertFalse(redis.values.containsKey("adding:$amina"), "kept as a hash, not a plain key")
        }

    /** Plain keys and hashes in memory, with the expiry each was last given. */
    private class MemoryRedis : RedisScope {
        override val prefix = "wal"
        val values = ConcurrentHashMap<String, String>()
        val hashes = ConcurrentHashMap<String, MutableMap<String, String>>()
        val ttls = ConcurrentHashMap<String, Duration>()

        override suspend fun get(key: String): String? = values[key]

        override suspend fun getPipelined(keys: List<String>): List<String?> = keys.map { values[it] }

        override suspend fun set(
            key: String,
            value: String,
            ttl: Duration,
        ) {
            values[key] = value
            ttls[key] = ttl
        }

        override suspend fun setIfAbsent(
            key: String,
            value: String,
            ttl: Duration,
        ): Boolean = error("not used")

        override suspend fun getAndDelete(key: String): String? = values.remove(key)

        override suspend fun delete(key: String): Boolean = values.remove(key) != null

        override suspend fun exists(key: String): Boolean = values.containsKey(key)

        override suspend fun increment(key: String, ttl: Duration): Long = error("not used")

        override suspend fun incrementBy(
            key: String,
            by: Long,
            ttl: Duration,
        ): Long = error("not used")

        override suspend fun incrementRenewing(key: String, ttl: Duration): Long = error("not used")

        override suspend fun hashSet(
            key: String,
            fields: Map<String, String>,
            ttl: Duration,
        ) {
            hashes.getOrPut(key) { ConcurrentHashMap() }.putAll(fields)
            ttls[key] = ttl
        }

        override suspend fun hashGetAll(key: String): Map<String, String> = hashes[key].orEmpty()

        override suspend fun hashTake(key: String): Map<String, String> = hashes.remove(key).orEmpty()

        override suspend fun hashDelete(key: String, field: String): Boolean = hashes[key]?.remove(field) != null

        override suspend fun hashIncrement(
            key: String,
            field: String,
            by: Long,
        ): Long? = error("not used")

        override suspend fun ttl(key: String): Duration? = ttls[key]

        override suspend fun publish(channel: String, message: String) = Unit

        override fun subscribe(channel: String): Flow<String> = emptyFlow()
    }
}
