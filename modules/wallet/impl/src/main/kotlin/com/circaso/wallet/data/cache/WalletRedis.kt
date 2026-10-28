package com.circaso.wallet.data.cache

import com.circaso.infrastructure.api.RedisScope
import com.circaso.shared.CircasoJson
import com.circaso.shared.UserId
import com.circaso.wallet.domain.models.Holding
import com.circaso.wallet.domain.ports.AddingChains
import com.circaso.wallet.domain.ports.BalanceCache
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.math.BigInteger
import java.time.Duration

/**
 * Wallet's Redis prefix: every key it writes is `wal:<purpose>:<id>` (Wallet → Data → Redis). The keys arrive with
 * their slices.
 */
const val REDIS_PREFIX = "wal"

/** `wal:balance:{circleWalletId}`: one chain's holdings as JSON, for 15 seconds. */
class RedisBalanceCache(private val redis: RedisScope) : BalanceCache {
    override suspend fun get(circleWalletId: String): List<Holding>? {
        val raw = redis.get(key(circleWalletId)) ?: return null
        return runCatching { CircasoJson.decodeFromString(LIST, raw) }
            .getOrNull()
            ?.map { it.toHolding() }
    }

    override suspend fun put(circleWalletId: String, holdings: List<Holding>) {
        redis.set(key(circleWalletId), CircasoJson.encodeToString(LIST, holdings.map { it.toCached() }), TTL)
    }

    override suspend fun clear(circleWalletId: String) {
        redis.delete(key(circleWalletId))
    }

    private fun key(circleWalletId: String) = "balance:$circleWalletId"

    /** A holding as cached; the amount as a string, since an 18-decimal token passes what a JSON number holds. */
    @Serializable
    private data class CachedHolding(
        val symbol: String,
        val tokenAddress: String,
        val decimals: Int,
        val usdPegged: Boolean,
        val amountMinor: String,
    ) {
        fun toHolding() = Holding(symbol, tokenAddress, decimals, usdPegged, BigInteger(amountMinor))
    }

    private fun Holding.toCached() = CachedHolding(symbol, tokenAddress, decimals, usdPegged, amountMinor.toString())

    private companion object {
        val TTL: Duration = Duration.ofSeconds(15)
        val LIST = ListSerializer(CachedHolding.serializer())
    }
}

/** `wal:adding:{userId}`: a hash of the chains being added (field: the chain), kept an hour from the last request. */
class RedisAddingChains(private val redis: RedisScope) : AddingChains {
    override suspend fun add(userId: UserId, blockchain: String) {
        redis.hashSet(key(userId), mapOf(blockchain to "1"), TTL)
    }

    override suspend fun list(userId: UserId): Set<String> = redis.hashGetAll(key(userId)).keys

    override suspend fun remove(userId: UserId, blockchain: String) {
        redis.hashDelete(key(userId), blockchain)
    }

    private fun key(userId: UserId) = "adding:$userId"

    private companion object {
        /** A challenge runs under a user token that lasts 60 minutes. */
        val TTL: Duration = Duration.ofHours(1)
    }
}
