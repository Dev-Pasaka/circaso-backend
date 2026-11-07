package com.circaso.wallet.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.domain.models.ChainHoldings
import com.circaso.wallet.domain.models.ChainWallet
import com.circaso.wallet.domain.models.ChainWalletState
import com.circaso.wallet.domain.models.CircleUser
import com.circaso.wallet.domain.models.Holding
import com.circaso.wallet.domain.models.StaffBalance
import com.circaso.wallet.domain.models.StaffChainWallet
import com.circaso.wallet.domain.models.StaffHoldings
import com.circaso.wallet.domain.repositories.WalletRepository
import com.circaso.wallet.domain.support.HoldingPrices
import com.circaso.wallet.domain.support.HoldingsReader
import com.circaso.wallet.domain.support.StaffReports
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID

/**
 * Staff: what a holder's wallet holds (decision 2026-11-08), a person's ([holderId] = their user id) or a group's
 * (its wallet id). Like W1 it reads Circle, the source of truth for balances, through the 15-second balance cache, and
 * prices each coin as W1 does ([HoldingPrices]); unlike W1 it is free of side effects: no Circle wallet sync, no
 * setup progress, and no `ActiveWallets.touch`, so a staff look never marks the holder active or changes what the
 * reconciliation pass looks at. Only our rows say which chains the wallet is on.
 *
 * Circle failing is not an error here: the wallets are listed without balances and [StaffHoldings.balancesAvailable]
 * says so, because staff still need to see that the wallet exists and on which chains.
 */
class GetStaffHoldings(
    private val wallets: WalletRepository,
    private val holdings: HoldingsReader,
    private val prices: HoldingPrices,
) {
    suspend operator fun invoke(holderId: UUID): Resource<StaffHoldings> {
        val holder = UserId(holderId)
        val user = wallets.circleUser(holder) ?: return Resource.success(StaffHoldings(false, emptyList(), null, true))
        val chains = wallets.wallets(holder)
        val reads = read(user, chains.filter { it.state != ChainWalletState.CLOSED })
        if (reads == null) {
            return Resource.success(
                StaffHoldings(true, chains.map { StaffChainWallet(it, emptyList()) }, null, balancesAvailable = false),
            )
        }
        val priced = prices.price(chains.map { ChainHoldings(it, reads[it.circleWalletId].orEmpty()) })
        val listed =
            priced.map { chain ->
                StaffChainWallet(chain.wallet, chain.holdings.map { StaffBalance(it, StaffReports.usdMicros(it)) })
            }
        val total = listed.flatMap { it.balances }.sumOf { it.usdMicros ?: 0L }
        return Resource.success(StaffHoldings(true, listed, total, balancesAvailable = true))
    }

    /** Every open chain's holdings by Circle wallet id, read at once (a few at a time); null if any read failed. */
    private suspend fun read(user: CircleUser, open: List<ChainWallet>): Map<String, List<Holding>>? {
        val results =
            coroutineScope {
                val permits = Semaphore(PARALLEL_READS)
                open
                    .map { wallet ->
                        async {
                            wallet.circleWalletId to
                                permits.withPermit { holdings.cached(user, wallet) }
                        }
                    }.awaitAll()
            }
        if (results.any { it.second is Resource.Error }) return null
        return results.associate { (id, read) -> id to (read as Resource.Success).data }
    }

    private companion object {
        /** As W1: a wallet is on a handful of chains, this only caps an odd one. */
        const val PARALLEL_READS = 4
    }
}
