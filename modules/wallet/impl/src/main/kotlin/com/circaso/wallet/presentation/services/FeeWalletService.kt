package com.circaso.wallet.presentation.services

import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.WalletNetwork
import com.circaso.wallet.domain.models.FeeDestination
import com.circaso.wallet.domain.models.FeeStaff
import com.circaso.wallet.domain.models.FeeWalletNetwork
import com.circaso.wallet.domain.models.FeeWithdrawal
import com.circaso.wallet.domain.support.FeeDestinations
import com.circaso.wallet.domain.support.FeeWalletBalances
import com.circaso.wallet.domain.support.FeeWalletSetup
import com.circaso.wallet.domain.support.FeeWalletsView
import com.circaso.wallet.domain.support.FeeWithdrawals
import com.circaso.wallet.domain.models.WithdrawalOrder
import kotlinx.coroutines.withContext
import java.time.Duration
import java.util.UUID

/**
 * Circaso's fee wallets (decision 2026-11-18): what staff read and do through `FeeWalletAdmin`, and the two jobs the
 * ops server runs ([ensureWallets] at start-up and every few minutes, [followWithdrawals] every few seconds). Each
 * piece of work runs with its network as the coroutine's, so anything it asks of the wallet goes to that network's
 * Circle account.
 */
class FeeWalletService(
    private val setup: FeeWalletSetup,
    private val balances: FeeWalletBalances,
    private val feeDestinations: FeeDestinations,
    private val feeWithdrawals: FeeWithdrawals,
) {
    suspend fun wallets(network: Network): FeeWalletsView = on(network) { balances.view(network) }

    suspend fun destinationDelay(): Duration = feeDestinations.delay()

    suspend fun destinations(network: Network?): List<FeeDestination> = feeDestinations.list(network)

    suspend fun destination(id: UUID): FeeDestination? = feeDestinations.find(id)

    suspend fun addDestination(
        network: Network,
        blockchain: String,
        address: String,
        label: String,
        by: FeeStaff,
    ): Resource<FeeDestination> = on(network) { feeDestinations.add(network, blockchain, address, label, by) }

    suspend fun removeDestination(id: UUID, by: FeeStaff): Resource<FeeDestination> = feeDestinations.remove(id, by)

    suspend fun withdrawals(
        network: Network?,
        before: UUID?,
        limit: Int,
    ): List<FeeWithdrawal> = feeWithdrawals.page(network, before, limit)

    suspend fun withdraw(request: WithdrawalOrder, by: FeeStaff): Resource<FeeWithdrawal> =
        on(request.network) { feeWithdrawals.request(request, by) }

    /** Every network's fee wallets, checked and created where they are missing; how many networks are `READY`. */
    suspend fun ensureWallets(): Int = ensureAll().count { it.status.name == "READY" }

    suspend fun ensureAll(): List<FeeWalletNetwork> =
        Network.entries.map { network ->
            on(network) {
                setup.ensure(network)
            }
        }

    suspend fun followWithdrawals(): Int = feeWithdrawals.follow()

    private suspend fun <T> on(network: Network, block: suspend () -> T): T =
        withContext(WalletNetwork(network)) { block() }
}
