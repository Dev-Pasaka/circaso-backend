package com.circaso.ramp.domain.support

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.CandidateFacts
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.EngineMode
import com.circaso.ramp.domain.models.EngineSettings
import com.circaso.ramp.domain.models.Exclusion
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.RoutingOrder
import com.circaso.ramp.domain.models.SimulatedShare
import com.circaso.ramp.domain.models.Simulation
import com.circaso.ramp.domain.models.SimulationCommand
import com.circaso.ramp.domain.ports.Pricing
import com.circaso.ramp.domain.repositories.OrderRepository
import com.circaso.shared.Resource
import java.time.Clock
import java.time.Instant

/**
 * What a draft of the engine's settings would do, with nothing written, reserved or sent: the last real orders (or
 * one amount) are routed again among **the merchants as they are now** (who is online, their float, their record),
 * once under the draft and once under what is in force, and each merchant's share of the first picks is added up.
 *
 * The share is exact, not sampled: under `ACTIVE` a merchant's chance of being picked first for an order is their
 * weight over the sum of the eligible weights; under `ROUND_ROBIN` and `SHADOW` round-robin picks, which over many
 * orders gives each eligible merchant an equal turn. What it can't know is who was online when those orders were
 * really made.
 */
class RoutingSimulator(
    private val facts: EngineFacts,
    private val book: EngineBook,
    private val settings: SettingsBook,
    private val orders: OrderRepository,
    private val pricing: Pricing,
    private val clock: Clock,
) {
    private class Tally(val displayName: String) {
        var share = 0.0
        var currentShare = 0.0
        var eligibleOrders = 0
        var qualitySum = 0.0
        var qualityCount = 0
        val exclusions = mutableMapOf<Exclusion, Int>()
    }

    suspend fun run(command: SimulationCommand): Resource<Simulation> {
        val current = book.of(command.network, command.direction).settings
        val draft = command.draft ?: current
        draft.problem()?.let { (field, what) -> return Resource.error(RampError.SettingsInvalid(field, what)) }
        val rampSettings = settings.of(command.network)
        val replayed =
            when (val asked = replayed(command, rampSettings)) {
                is Resource.Error -> return asked
                is Resource.Success -> asked.data
            }
        val now = clock.instant()
        val tallies = linkedMapOf<MerchantId, Tally>()
        val pools = mutableMapOf<Pair<String, EngineSettings>, Gathered>()
        var unroutable = 0
        replayed.forEach { (blockchain, order) ->
            val pool = PoolScope(command.network, blockchain, command.direction)
            // Each settings' own pool: a limit or a statistics window the draft changes is read as the draft has it.
            val underDraft = pools.getOrPut(blockchain to draft) { facts.gather(pool, rampSettings, draft, true) }
            val underCurrent = pools.getOrPut(blockchain to current) { facts.gather(pool, rampSettings, current, true) }
            underDraft.candidates.forEach { (id, candidate) ->
                tallies.getOrPut(id) { Tally(candidate.merchant.displayName) }
            }
            val draftShares = shares(order, underDraft.facts, draft, now)
            if (draftShares.isEmpty()) unroutable += 1
            underDraft.facts.forEach { one -> tally(tallies.getValue(one.merchantId), one, order, draft, now) }
            draftShares.forEach { (id, share) -> tallies.getValue(id).share += share }
            shares(order, underCurrent.facts, current, now).forEach { (id, share) ->
                tallies[id]?.let { it.currentShare += share }
            }
        }
        return Resource.success(simulation(command, draft, current, replayed.size, unroutable, tallies))
    }

    private fun tally(
        tally: Tally,
        one: CandidateFacts,
        order: RoutingOrder,
        draft: EngineSettings,
        now: Instant,
    ) {
        val exclusion = Scoring.exclusion(one, order)
        if (exclusion != null) {
            tally.exclusions.merge(exclusion, 1, Int::plus)
            return
        }
        tally.eligibleOrders += 1
        if (draft.mode != EngineMode.ROUND_ROBIN) {
            tally.qualitySum += Scoring.score(one, order, draft, now).quality
            tally.qualityCount += 1
        }
    }

    /** Each eligible merchant's chance of being the first pick for [order] under [engine]. */
    private fun shares(
        order: RoutingOrder,
        pool: List<CandidateFacts>,
        engine: EngineSettings,
        now: Instant,
    ): Map<MerchantId, Double> {
        val eligible = pool.filter { Scoring.exclusion(it, order) == null }
        if (eligible.isEmpty()) return emptyMap()
        if (engine.mode != EngineMode.ACTIVE) return eligible.associate { it.merchantId to 1.0 / eligible.size }
        val weights = eligible.associate { it.merchantId to Scoring.score(it, order, engine, now).weight }
        val total = weights.values.sum()
        return weights.mapValues { (_, weight) -> if (total > 0) weight / total else 1.0 / eligible.size }
    }

    /** The orders to replay, each with the chain it was (or would be) delivered on. */
    private suspend fun replayed(
        command: SimulationCommand,
        rampSettings: RampSettings,
    ): Resource<List<Pair<String, RoutingOrder>>> {
        val fiatMinor = command.fiatMinor
        if (fiatMinor == null) {
            val last = (command.lastOrders ?: DEFAULT_ORDERS).coerceIn(1, MAX_ORDERS)
            return Resource.success(
                orders.recent(command.network, command.direction, last).map {
                    val need = needOf(it.assetMinor, it.fiat.amountMinor, command.direction)
                    it.blockchain to RoutingOrder(command.direction, it.fiat.amountMinor, need)
                },
            )
        }
        val blockchain = rampSettings.chains.firstOrNull() ?: return Resource.success(emptyList())
        val assetMinor =
            when (val priced = pricing.price(command.direction, fiatMinor, null, rampSettings.feeBps)) {
                is Resource.Error -> return priced
                is Resource.Success -> priced.data.assetMinor
            }
        val order = RoutingOrder(command.direction, fiatMinor, needOf(assetMinor, fiatMinor, command.direction))
        return Resource.success(listOf(blockchain to order))
    }

    /** What the merchant's float must cover: the USDC to release when buying, the shillings to pay out when selling. */
    private fun needOf(
        assetMinor: Long,
        fiatMinor: Long,
        direction: Direction,
    ): Long = if (direction == Direction.ON_RAMP) assetMinor else fiatMinor

    private fun simulation(
        command: SimulationCommand,
        draft: EngineSettings,
        current: EngineSettings,
        replayed: Int,
        unroutable: Int,
        tallies: Map<MerchantId, Tally>,
    ): Simulation {
        val orders = replayed.coerceAtLeast(1)
        return Simulation(
            network = command.network,
            direction = command.direction,
            mode = draft.mode,
            currentMode = current.mode,
            orders = replayed,
            unroutable = unroutable,
            shares =
                tallies
                    .map { (id, tally) ->
                        SimulatedShare(
                            merchantId = id,
                            displayName = tally.displayName,
                            share = tally.share / orders,
                            currentShare = tally.currentShare / orders,
                            eligibleOrders = tally.eligibleOrders,
                            exclusions = tally.exclusions.toMap(),
                            quality = if (tally.qualityCount == 0) null else tally.qualitySum / tally.qualityCount,
                        )
                    }.sortedByDescending { it.share },
        )
    }

    private companion object {
        const val DEFAULT_ORDERS = 50
        const val MAX_ORDERS = 500
    }
}
