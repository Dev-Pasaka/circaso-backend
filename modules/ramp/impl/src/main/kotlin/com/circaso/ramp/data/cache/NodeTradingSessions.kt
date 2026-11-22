package com.circaso.ramp.data.cache

import com.circaso.infrastructure.api.RedisScope
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.models.UnlockedKeys
import com.circaso.ramp.domain.ports.TradingSessions
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Unlocked payment keys, **in this process's memory and nowhere else**. `rmp:session:{merchantId}` in Redis holds only
 * this node's id, for the length of the trading window: it says the merchant is online and where their keys are, never
 * what they are. A restart empties the map, so this node's merchants are offline until they unlock again; their Redis
 * marks name a node id that no longer exists and expire with the window.
 *
 * One node is enough for now: nothing hands a provider call to the node that holds the keys, so a merchant unlocked on
 * another node isn't a candidate here.
 */
class NodeTradingSessions(private val redis: RedisScope, private val nodeId: String, private val clock: Clock) :
    TradingSessions {
    private val keys = ConcurrentHashMap<MerchantId, UnlockedKeys>()

    override suspend fun unlock(
        merchantId: MerchantId,
        keys: UnlockedKeys,
        until: Instant,
    ) {
        this.keys[merchantId] = keys
        val window = Duration.between(clock.instant(), until).takeIf { !it.isNegative && !it.isZero } ?: return
        redis.set(mark(merchantId), nodeId, window)
    }

    override fun of(merchantId: MerchantId): UnlockedKeys? = keys[merchantId]

    override fun replace(merchantId: MerchantId, keys: UnlockedKeys) {
        this.keys.computeIfPresent(merchantId) { _, _ -> keys }
    }

    override suspend fun end(merchantId: MerchantId, keepKeys: Boolean) {
        if (!keepKeys) keys.remove(merchantId)
        redis.delete(mark(merchantId))
    }

    override fun held(): Set<MerchantId> = keys.keys.toSet()

    private fun mark(merchantId: MerchantId) = "session:$merchantId"

    /** Never the keys. */
    override fun toString(): String = "NodeTradingSessions(${keys.size} unlocked)"
}
