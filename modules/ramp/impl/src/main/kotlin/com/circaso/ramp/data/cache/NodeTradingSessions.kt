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
 * what they are. A session with no end (online until stopped) is marked for [OPEN_ENDED_MARK] at a time, renewed by
 * `ramp.end-sessions` while this node runs. A restart empties the map, so this node's merchants are offline until they
 * unlock again (`ramp.end-sessions` ends their sessions with `RESTART`); their Redis marks name a node id that no
 * longer exists and expire with the window, or within [OPEN_ENDED_MARK].
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
        until: Instant?,
    ) {
        this.keys[merchantId] = keys
        if (until == null) return redis.set(mark(merchantId), nodeId, OPEN_ENDED_MARK)
        val window = Duration.between(clock.instant(), until).takeIf { !it.isNegative && !it.isZero } ?: return
        redis.set(mark(merchantId), nodeId, window)
    }

    override suspend fun renew(merchantId: MerchantId) {
        if (keys.containsKey(merchantId)) redis.set(mark(merchantId), nodeId, OPEN_ENDED_MARK)
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

    private companion object {
        /** Ten runs of `ramp.end-sessions`: a missed run or two never lets a live session's mark lapse. */
        val OPEN_ENDED_MARK: Duration = Duration.ofMinutes(10)
    }
}
