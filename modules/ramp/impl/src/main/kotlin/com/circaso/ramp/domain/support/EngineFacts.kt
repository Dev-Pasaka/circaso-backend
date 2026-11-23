package com.circaso.ramp.domain.support

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.MerchantFloat
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.QuoteId
import com.circaso.ramp.domain.models.Candidate
import com.circaso.ramp.domain.models.CandidateFacts
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.EngineSettings
import com.circaso.ramp.domain.models.MerchantState
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.RoutingOrder
import com.circaso.ramp.domain.models.Subject
import com.circaso.ramp.domain.ports.KesFloat
import com.circaso.ramp.domain.ports.KycGate
import com.circaso.ramp.domain.ports.TradingSessions
import com.circaso.ramp.domain.repositories.CommissionRepository
import com.circaso.ramp.domain.repositories.MerchantRepository
import com.circaso.ramp.domain.repositories.PayoutHoldRepository
import com.circaso.ramp.domain.repositories.ReservationRepository
import com.circaso.shared.Network
import com.circaso.shared.Resource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Clock

/** Whose merchants to look at: the chain's pool, for one direction. [kesFloat] is the caller's (an off-ramp's). */
data class PoolScope(
    val network: Network,
    val blockchain: String,
    val direction: Direction,
    val kesFloat: KesFloat? = null,
)

/** What is being routed, and for which quote. [kesFloat] is the caller's, and only an off-ramp needs it. */
data class RoutingCommand(
    val quoteId: QuoteId,
    val network: Network,
    val blockchain: String,
    val order: RoutingOrder,
    val kesFloat: KesFloat? = null,
) {
    val pool: PoolScope get() = PoolScope(network, blockchain, order.direction, kesFloat)
}

/** The merchants looked at for a routing, with everything read about each. */
data class Gathered(
    val facts: List<CandidateFacts>,
    val candidates: Map<MerchantId, Candidate>,
    /**
     * Buying: each wallet's USDC as the custodian reported it; selling: each merchant's payout balance as the provider
     * reported it. Before anything spoken for is taken off.
     */
    val balances: Map<MerchantId, Long>,
    /** Whether a float couldn't be read because the custodian was out of reach (not because it refused). */
    val custodyDown: Boolean,
)

/**
 * Reads what the engine needs to know about each merchant in the pool: the cheap facts first (state, window, keys,
 * identity, direction, limit), then the float of those still standing (a call to the custodian each, made together),
 * then their statistics. Nothing here decides anything: [Scoring] and [Router] do, from these facts alone.
 */
class EngineFacts(
    private val merchants: MerchantRepository,
    private val reservations: ReservationRepository,
    private val commissions: CommissionRepository,
    private val float: MerchantFloat,
    private val sessions: TradingSessions,
    private val kyc: KycGate,
    private val statsBook: MerchantStatsBook,
    private val clock: Clock,
    private val payoutHolds: PayoutHoldRepository,
) {
    private class Read(val facts: CandidateFacts, val balance: Long?, val custodyDown: Boolean)

    private class Free(val minor: Long?, val balance: Long?, val custodyDown: Boolean)

    /** Every merchant with a float wallet on the chain. */
    suspend fun pool(network: Network, blockchain: String): List<Candidate> =
        merchants.pool(network, blockchain, POOL_LIMIT)

    /**
     * The facts of every merchant in the pool for an order in the request's direction. Floats and statistics are read
     * for whoever passes the checks that need neither, whatever the amount, so one gathering serves any number of
     * amounts (the simulation replays many). [scores] false leaves the statistics unread: round-robin never looks.
     */
    suspend fun gather(
        request: PoolScope,
        settings: RampSettings,
        engine: EngineSettings,
        scores: Boolean,
    ): Gathered {
        val pool = pool(request.network, request.blockchain)
        val read = coroutineScope { pool.map { async { read(it, request, settings, engine, scores) } }.awaitAll() }
        return Gathered(
            facts = read.map { it.facts },
            candidates = pool.associateBy { it.merchant.merchantId },
            balances = read.mapNotNull { one -> one.balance?.let { one.facts.merchantId to it } }.toMap(),
            custodyDown = read.any { it.custodyDown },
        )
    }

    /** The facts that need neither a float nor a statistic. */
    suspend fun cheap(
        candidate: Candidate,
        direction: Direction,
        settings: RampSettings,
        engine: EngineSettings,
    ): CandidateFacts {
        val merchant = candidate.merchant
        val merchantId = merchant.merchantId
        return CandidateFacts(
            merchantId = merchantId,
            approved = merchant.state == MerchantState.APPROVED,
            kycCleared = !settings.enforcesKyc || kyc.isVerified(Subject.merchant(merchantId), settings.kycEnvironment),
            online = merchant.onlineUntil?.isAfter(clock.instant().plus(settings.windowOf(direction))) == true,
            hasKeys = sessions.of(merchantId)?.provider == settings.collectionsProvider,
            paymentsIssue = merchant.hasIssue(direction),
            offersDirection = merchant.offers(direction),
            limitMinor = merchant.orderLimit(engine),
            floatMinor = null,
            lastAssignedAt = merchant.lastAssignedAt,
            completedTrades = 0,
            stats = null,
        )
    }

    private suspend fun read(
        candidate: Candidate,
        request: PoolScope,
        settings: RampSettings,
        engine: EngineSettings,
        scores: Boolean,
    ): Read {
        val direction = request.direction
        val cheap = cheap(candidate, direction, settings, engine)
        if (Scoring.beforeFloat(cheap, NO_AMOUNT) != null) return Read(cheap, null, false)
        val merchantId = cheap.merchantId
        val free =
            when (direction) {
                Direction.ON_RAMP -> freeUsdc(candidate)
                Direction.OFF_RAMP -> freeKes(merchantId, request.kesFloat)
            }
        val trades = statsBook.trades(merchantId, request.network)
        val stats = if (scores) statsBook.stats(merchantId, request.network, direction, engine) else null
        val facts = cheap.copy(floatMinor = free.minor, completedTrades = trades, stats = stats)
        return Read(facts, free.balance, free.custodyDown)
    }

    /** The wallet's USDC less what is promised to open orders and what is held for Circaso; null when unreadable. */
    private suspend fun freeUsdc(candidate: Candidate): Free {
        val wallet = candidate.wallet
        return when (val held = float.balance(wallet.network, wallet.custodyWalletId)) {
            is Resource.Error -> {
                Free(null, null, custodyDown = held.error is IntegrationError.Unavailable)
            }

            is Resource.Success -> {
                val balance =
                    held.data.amountMinor
                        .min(Long.MAX_VALUE.toBigInteger())
                        .toLong()
                val spoken =
                    reservations.openTotal(wallet.merchantId, wallet.network) +
                        commissions.owedTotal(wallet.merchantId, wallet.network)
                Free(balance - spoken, balance, custodyDown = false)
            }
        }
    }

    /**
     * Selling: the merchant's payout balance as the provider reads it now, less what their open sales hold (both
     * networks: one provider account pays for both); null when it can't be read.
     */
    private suspend fun freeKes(merchantId: MerchantId, kesFloat: KesFloat?): Free {
        val balance = kesFloat?.of(merchantId) ?: return Free(null, null, custodyDown = false)
        return Free(balance - payoutHolds.openTotal(merchantId), balance, custodyDown = false)
    }

    companion object {
        /** More merchants than the ramp will have for a long time; a bound, so a routing never reads without one. */
        private const val POOL_LIMIT = 500

        /** An order of nothing: only the checks that don't depend on an amount can fail it. */
        val NO_AMOUNT = RoutingOrder(Direction.ON_RAMP, fiatMinor = 0, needMinor = 0)
    }
}
