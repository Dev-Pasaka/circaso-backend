package com.circaso.ramp.domain.support

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.EngineSettings
import com.circaso.ramp.domain.models.MerchantStats
import com.circaso.ramp.domain.repositories.CommissionRepository
import com.circaso.ramp.domain.repositories.OrderEventRepository
import com.circaso.shared.Network
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * A merchant's statistics, as the engine and the portal read them (decision 2026-11-23): their last
 * [EngineSettings.statsOrders] counted orders within [EngineSettings.statsDays] days on one network and direction,
 * computed by [Stats] from the order timeline, and how many trades they have completed.
 *
 * **Kept for [ttl] (30 seconds) per process.** Computing them is three indexed reads per merchant; an order is routed
 * among a handful of merchants, so the copy only saves repeating those reads for every order of a busy minute. It
 * means a finished order moves its merchant's score at most half a minute late, which no ranking needs sooner.
 */
class MerchantStatsBook(
    private val events: OrderEventRepository,
    private val commissions: CommissionRepository,
    private val clock: Clock,
    private val ttl: Duration = TTL,
) {
    private data class StatsKey(
        val merchantId: MerchantId,
        val network: Network,
        val direction: Direction,
        val orders: Int,
        val days: Int,
    )

    private class Kept<T>(val value: T, val at: Instant)

    private val stats = ConcurrentHashMap<StatsKey, Kept<MerchantStats>>()
    private val trades = ConcurrentHashMap<Pair<MerchantId, Network>, Kept<Int>>()

    suspend fun stats(
        merchantId: MerchantId,
        network: Network,
        direction: Direction,
        settings: EngineSettings,
    ): MerchantStats {
        val key = StatsKey(merchantId, network, direction, settings.statsOrders, settings.statsDays)
        stats[key]?.takeIf(::isFresh)?.let { return it.value }
        val since = clock.instant().minus(Duration.ofDays(settings.statsDays.toLong()))
        val counted = events.counted(merchantId, network, direction, since, settings.statsOrders)
        val computed = Stats.of(counted, events.notCounted(merchantId, network, direction, since))
        stats[key] = Kept(computed, clock.instant())
        return computed
    }

    /** Trades completed on [network], ever and in both directions: what the newcomer boost fades with. */
    suspend fun trades(merchantId: MerchantId, network: Network): Int {
        val key = merchantId to network
        trades[key]?.takeIf(::isFresh)?.let { return it.value }
        val counted = commissions.completedCount(merchantId, network)
        trades[key] = Kept(counted, clock.instant())
        return counted
    }

    private fun isFresh(kept: Kept<*>): Boolean = clock.instant().isBefore(kept.at.plus(ttl))

    companion object {
        val TTL: Duration = Duration.ofSeconds(30)
    }
}
