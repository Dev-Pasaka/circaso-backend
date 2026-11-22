package com.circaso.ramp.domain.support

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.MerchantFloat
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.Candidate
import com.circaso.ramp.domain.models.Order
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.ports.TradingSessions
import com.circaso.ramp.domain.ports.Transactor
import com.circaso.ramp.domain.repositories.CommissionRepository
import com.circaso.ramp.domain.repositories.MerchantRepository
import com.circaso.ramp.domain.repositories.MerchantWalletRepository
import com.circaso.ramp.domain.repositories.OrderRepository
import com.circaso.ramp.domain.repositories.ReservationRepository
import com.circaso.shared.Network
import com.circaso.shared.Resource
import java.time.Clock

/**
 * The merchant engine, in its simple first version: the candidates are approved merchants with a float wallet on the
 * order's network and chain whose trading window outlasts the order **and whose payment keys this node holds, for
 * the collections provider the network uses now**, taken
 * least recently assigned first so equal merchants take turns. (Ranking by recent success rate, splitting an order and
 * handing work to the node that holds a merchant's keys arrive later: with one node, a merchant unlocked elsewhere is
 * simply not a candidate here.)
 *
 * **Pick and reserve are one transaction; reading the custodian is not inside it.** A candidate's balance is read
 * first, outside any transaction. Then, under the merchant's row lock, the order and its reservation are written only
 * if `balance - open reservations - what is held for Circaso` still covers the amount. The lock serialises
 * reservations per merchant, so two orders can never both fit into the last of a merchant's float.
 */
class Engine(
    private val merchants: MerchantRepository,
    private val wallets: MerchantWalletRepository,
    private val reservations: ReservationRepository,
    private val commissions: CommissionRepository,
    private val orders: OrderRepository,
    private val transactor: Transactor,
    private val float: MerchantFloat,
    private val sessions: TradingSessions,
    private val clock: Clock,
) {
    /** Whether anybody could take an order on [blockchain] right now (their float isn't looked at). */
    suspend fun anyoneOnline(
        network: Network,
        blockchain: String,
        settings: RampSettings,
    ): Boolean = candidates(network, blockchain, settings).isNotEmpty()

    /** The order [draft] makes for the merchant picked, written with its reservation; or why nobody could take it. */
    suspend fun reserve(
        network: Network,
        blockchain: String,
        assetMinor: Long,
        settings: RampSettings,
        draft: (Candidate) -> Order,
    ): Resource<Order> {
        var custodyDown = false
        for (candidate in candidates(network, blockchain, settings)) {
            val balance =
                when (val held = float.balance(network, candidate.wallet.custodyWalletId)) {
                    is Resource.Success -> {
                        held.data.amountMinor
                            .min(Long.MAX_VALUE.toBigInteger())
                            .toLong()
                    }

                    is Resource.Error -> {
                        custodyDown = custodyDown || held.error is IntegrationError.Unavailable
                        continue
                    }
                }
            val reserved = reserveWith(candidate, balance, assetMinor, settings, draft)
            if (reserved is Resource.Success || (reserved as Resource.Error).error != RampError.NoMerchantAvailable) {
                return reserved
            }
        }
        return Resource.error(if (custodyDown) RampError.CustodyUnavailable else RampError.NoMerchantAvailable)
    }

    private suspend fun candidates(
        network: Network,
        blockchain: String,
        settings: RampSettings,
    ): List<Candidate> =
        merchants
            .candidates(network, blockchain, until = clock.instant().plus(settings.paymentWindow))
            .filter { sessions.of(it.merchant.merchantId)?.provider == settings.collectionsProvider }
            .sortedWith(compareBy(nullsFirst()) { it.merchant.lastAssignedAt })

    private suspend fun reserveWith(
        candidate: Candidate,
        balance: Long,
        assetMinor: Long,
        settings: RampSettings,
        draft: (Candidate) -> Order,
    ): Resource<Order> =
        transactor.attempt("orders.reserve") {
            val now = clock.instant()
            val merchantId = candidate.merchant.merchantId
            val merchant = merchants.lock(merchantId)
            val wallet = wallets.find(merchantId, candidate.wallet.network)
            when {
                merchant == null || wallet == null -> {
                    Resource.error(RampError.NoMerchantAvailable)
                }

                !merchant.isTradingUntil(now.plus(settings.paymentWindow)) -> {
                    Resource.error(RampError.NoMerchantAvailable)
                }

                wallet.blockchain != candidate.wallet.blockchain -> {
                    Resource.error(RampError.NoMerchantAvailable)
                }

                freeFloat(balance, wallet.network, merchant.merchantId) < assetMinor -> {
                    Resource.error(RampError.NoMerchantAvailable)
                }

                else -> {
                    val order = draft(Candidate(merchant, wallet))
                    if (!orders.insert(order)) {
                        Resource.error(RampError.OrderInProgress)
                    } else {
                        reservations.insert(order, now)
                        merchants.markAssigned(merchant.merchantId, now)
                        Resource.success(order)
                    }
                }
            }
        }

    private suspend fun freeFloat(
        balance: Long,
        network: Network,
        merchantId: MerchantId,
    ): Long = balance - reservations.openTotal(merchantId, network) - commissions.owedTotal(merchantId, network)
}
