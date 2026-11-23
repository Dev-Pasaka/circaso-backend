package com.circaso.ramp.domain.support

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.Candidate
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.EngineMode
import com.circaso.ramp.domain.models.EngineSettingsVersion
import com.circaso.ramp.domain.models.Order
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.RoutingDecision
import com.circaso.ramp.domain.models.RoutingOrder
import com.circaso.ramp.domain.models.RoutingOutcome
import com.circaso.ramp.domain.models.RoutingPlan
import com.circaso.ramp.domain.models.RoutingStep
import com.circaso.ramp.domain.ports.Transactor
import com.circaso.ramp.domain.repositories.CommissionRepository
import com.circaso.ramp.domain.repositories.MerchantRepository
import com.circaso.ramp.domain.repositories.MerchantWalletRepository
import com.circaso.ramp.domain.repositories.OrderRepository
import com.circaso.ramp.domain.repositories.ReservationRepository
import com.circaso.ramp.domain.repositories.RoutingLogRepository
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.Uuid7
import java.time.Clock
import java.util.UUID
import kotlin.random.Random

/** A routing in progress: the logged decision, the plan, and the merchants behind it. */
data class Routing(
    val decisionId: UUID,
    val plan: RoutingPlan,
    val gathered: Gathered,
    val engine: EngineSettingsVersion,
    val order: RoutingOrder,
) {
    /** The merchants to try, in the plan's order. */
    val ranked: List<Candidate> get() = plan.ranked.mapNotNull { gathered.candidates[it] }

    /** Whether a float couldn't be read because the custodian was out of reach. */
    val custodyDown: Boolean get() = gathered.custodyDown
}

/**
 * The merchant engine (decision 2026-11-23; the first slice took the least recently assigned merchant).
 *
 * **Eligibility** is a hard filter, the same in every mode: approved, identity verified where the network requires
 * it, online for the whole payment window with keys on this node for the network's provider, nothing to fix at their
 * provider, offering the direction, the order within their limit, and free float that covers it. Everyone left out
 * is recorded with the reason.
 *
 * **Ranking** depends on the settings' mode. `ROUND_ROBIN`: the least recently assigned first. `ACTIVE`: a weighted
 * random draw without replacement over `W = max(Q, floor)^alpha × (1 + hunger) × (1 + newcomer) × sizeFit`, which
 * gives the whole failover order at once. `SHADOW`: that draw is made and logged, and round-robin still picks.
 *
 * **Pick and reserve are one transaction; reading the custodian is not inside it.** Facts are read first, outside
 * any transaction ([EngineFacts]). Then, under the merchant's row lock, everything that could have changed is checked
 * again and the order and its reservation are written only if it still holds. The lock serialises reservations per
 * merchant, so two orders can never both fit into the last of a merchant's float.
 *
 * **Every routing is logged** (`ramp.routing_decisions`): written before the first attempt, finished with the steps.
 */
class Engine(
    private val facts: EngineFacts,
    private val book: EngineBook,
    private val merchants: MerchantRepository,
    private val wallets: MerchantWalletRepository,
    private val reservations: ReservationRepository,
    private val commissions: CommissionRepository,
    private val orders: OrderRepository,
    private val transactor: Transactor,
    private val log: RoutingLogRepository,
    private val random: Random,
    private val clock: Clock,
) {
    /** Whether anybody could take a purchase on [blockchain] right now (their float and limit aren't looked at). */
    suspend fun anyoneOnline(
        network: Network,
        blockchain: String,
        settings: RampSettings,
    ): Boolean {
        val engine = book.of(network, Direction.ON_RAMP).settings
        return facts.pool(network, blockchain).any {
            Scoring.beforeFloat(facts.cheap(it, Direction.ON_RAMP, settings, engine), EngineFacts.NO_AMOUNT) == null
        }
    }

    /** Decides who may take [request] and in which order to try them, and logs the decision. Nothing is reserved. */
    suspend fun route(request: RoutingCommand, settings: RampSettings): Routing {
        val now = clock.instant()
        val engine = book.of(request.network, request.order.direction)
        val scores = engine.settings.mode != EngineMode.ROUND_ROBIN
        val gathered = facts.gather(request.pool, settings, engine.settings, scores)
        val plan = Router.plan(request.order, gathered.facts, engine.settings, now, random)
        val decision =
            RoutingDecision(
                decisionId = Uuid7.generate(now),
                quoteId = request.quoteId,
                orderId = null,
                merchantId = null,
                network = request.network,
                order = request.order,
                settingsVersion = engine.version,
                plan = plan,
                outcome = if (plan.ranked.isEmpty()) RoutingOutcome.NO_CANDIDATE else RoutingOutcome.PENDING,
                steps = emptyList(),
                createdAt = now,
            )
        log.insert(decision)
        return Routing(decision.decisionId, plan, gathered, engine, request.order)
    }

    /**
     * Writes the order [draft] makes for [candidate] with its reservation, if under the merchant's row lock they can
     * still take it; `ramp.no_merchant_available` when they can't any more (the caller tries the next). Buying only:
     * the off-ramp holds USDC the user sent instead of promising float, and writes its own hold.
     */
    suspend fun reserve(
        routing: Routing,
        candidate: Candidate,
        settings: RampSettings,
        draft: (Candidate) -> Order,
    ): Resource<Order> =
        transactor.attempt("orders.reserve") {
            val now = clock.instant()
            val merchantId = candidate.merchant.merchantId
            val merchant = merchants.lock(merchantId)
            val wallet = wallets.find(merchantId, candidate.wallet.network)
            val fits =
                merchant != null && wallet != null &&
                    merchant.isTradingUntil(now.plus(settings.paymentWindow)) &&
                    merchant.paymentsIssue == null &&
                    wallet.blockchain == candidate.wallet.blockchain &&
                    merchant.offers(routing.order.direction) &&
                    routing.order.fiatMinor <= merchant.orderLimit(routing.engine.settings) &&
                    freeFloat(routing, merchantId, wallet.network) >= routing.order.needMinor
            if (fits) write(draft(Candidate(merchant, wallet))) else Resource.error(RampError.NoMerchantAvailable)
        }

    private suspend fun write(order: Order): Resource<Order> {
        if (!orders.insert(order)) return Resource.error(RampError.OrderInProgress)
        val now = clock.instant()
        reservations.insert(order, now)
        merchants.markAssigned(order.merchantId, now)
        return Resource.success(order)
    }

    /** Finishes the routing's log row: how it ended, who took the order if anybody did, and each step. */
    suspend fun conclude(
        routing: Routing,
        outcome: RoutingOutcome,
        steps: List<RoutingStep>,
        order: Order? = null,
    ) {
        log.finish(routing.decisionId, order?.orderId, order?.merchantId, outcome, steps)
    }

    /**
     * The balance the routing read from the custodian, less what is spoken for **now**, under the lock: open
     * reservations and what is held for Circaso. Nothing was read for the merchant: nothing is free.
     */
    private suspend fun freeFloat(
        routing: Routing,
        merchantId: MerchantId,
        network: Network,
    ): Long {
        val balance = routing.gathered.balances[merchantId] ?: return Long.MIN_VALUE
        return balance - reservations.openTotal(merchantId, network) - commissions.owedTotal(merchantId, network)
    }
}
