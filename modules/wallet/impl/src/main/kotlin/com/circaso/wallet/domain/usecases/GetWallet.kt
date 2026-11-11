package com.circaso.wallet.domain.usecases

import com.circaso.integrations.api.ExchangeRates
import com.circaso.integrations.api.StablecoinCustody
import com.circaso.integrations.api.UsdRate
import com.circaso.shared.Resource
import com.circaso.wallet.domain.models.ChainHoldings
import com.circaso.wallet.domain.models.ChainWallet
import com.circaso.wallet.domain.models.ChainWalletState
import com.circaso.wallet.domain.models.CircleUser
import com.circaso.wallet.domain.models.PaymentHold
import com.circaso.wallet.domain.models.WalletAccess
import com.circaso.wallet.domain.models.WalletQuery
import com.circaso.wallet.domain.models.WalletStatus
import com.circaso.wallet.domain.models.WalletView
import com.circaso.wallet.domain.ports.ActiveWallets
import com.circaso.wallet.domain.ports.AddingChains
import com.circaso.wallet.domain.ports.PaymentHolds
import com.circaso.wallet.domain.repositories.WalletRepository
import com.circaso.wallet.domain.support.Holders
import com.circaso.wallet.domain.support.HoldingPrices
import com.circaso.wallet.domain.support.HoldingsReader
import com.circaso.wallet.domain.support.SetupProgress
import com.circaso.wallet.domain.support.Valuation
import com.circaso.wallet.domain.support.WalletSync
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * W1 `GET /wallet`: the wallet's status, each chain it is on with the assets held there, the totals over all chains
 * and, with `currency`, their worth in that currency. While the wallet is being set up or a chain added, Circle's
 * wallet list is read first, so a wallet the user just finished in the SDK shows at once (while setting up, at most
 * once per [SetupProgress] window, since the app polls).
 *
 * Each holding carries its US dollar price ([HoldingPrices]; decision 2026-11-06), with or without `currency`, so the
 * app can turn an amount typed in the person's money into any coin they hold. The price feed is asked only after the
 * balances, and only when a coin other than a dollar stablecoin is held.
 *
 * The fiat part is never an error: no rate (an unknown currency, the source down too long) leaves it out. A read
 * marks the user active ([ActiveWallets]) for `wallet.reconcile-active`.
 *
 * The chains are read at the same time (at most [PARALLEL_READS] at once), and the rate alongside them: each Circle
 * balance call takes 200 to 400 ms, so one after another a wallet on four chains took over a second. A chain that
 * fails still fails the whole read, and the error answered is the first in chain order, as when they ran in turn.
 */
class GetWallet(
    private val wallets: WalletRepository,
    private val sync: WalletSync,
    private val adding: AddingChains,
    private val holdings: HoldingsReader,
    private val rates: ExchangeRates,
    private val custody: StablecoinCustody,
    private val active: ActiveWallets,
    private val holders: Holders,
    private val progress: SetupProgress,
    private val prices: HoldingPrices,
    private val holds: PaymentHolds,
) {
    suspend operator fun invoke(query: WalletQuery): Resource<WalletView> {
        val access =
            when (val seen = holders.view(query.userId, query.walletId)) {
                is Resource.Success -> seen.data
                is Resource.Error -> return seen
            }
        val holder = access.owner.holder
        // The caller's own hold: theirs are the payments it pauses, from this wallet or any they sign for.
        val hold = holds.active(query.userId)
        val user = wallets.circleUser(holder) ?: return Resource.success(empty(WalletStatus.NONE, access, hold))
        // The user is looking: the light reconciliation pass checks their wallet for a lost webhook for a while.
        active.touch(holder)
        val open = current(user).filter { it.state != ChainWalletState.CLOSED }
        if (open.isEmpty()) return Resource.success(empty(WalletStatus.SETTING_UP, access, hold))

        val (reads, rate) =
            coroutineScope {
                val rate = query.currency?.let { currency -> async { rates.usdRate(currency) } }
                val permits = Semaphore(PARALLEL_READS)
                val reads = open.map { wallet -> async { permits.withPermit { holdings.cached(user, wallet) } } }
                reads.awaitAll() to rate?.await()
            }
        val chains =
            open.zip(reads).map { (wallet, read) ->
                when (read) {
                    is Resource.Success -> ChainHoldings(wallet, read.data, receives(wallet.blockchain))
                    is Resource.Error -> return read
                }
            }
        return Resource.success(view(prices.price(chains), rate).copy(access = access, hold = hold))
    }

    /** Our rows, after asking Circle when something is still on its way; Circle being down leaves our rows. */
    private suspend fun current(user: CircleUser): List<ChainWallet> {
        val known = wallets.wallets(user.userId)
        if (known.isNotEmpty() && adding.list(user.userId).isEmpty()) return known
        if (known.none { it.state == ChainWalletState.ACTIVE }) return progress.refresh(user) ?: known
        return (sync.sync(user) as? Resource.Success)?.data ?: known
    }

    private fun view(chains: List<ChainHoldings>, rate: UsdRate?): WalletView {
        val priced =
            if (rate == null) {
                chains
            } else {
                chains.map { chain ->
                    chain.copy(holdings = chain.holdings.map { it.copy(fiatAmount = Valuation.fiatOf(it, rate)) })
                }
            }
        return WalletView(
            status = WalletStatus.ACTIVE,
            chains = priced,
            totals = Valuation.totals(priced),
            fiat = rate?.let { Valuation.fiat(priced, it) },
            hold = null,
        )
    }

    private fun receives(blockchain: String): Boolean = custody.supported.find(blockchain)?.receiveEnabled == true

    private fun empty(
        status: WalletStatus,
        access: WalletAccess,
        hold: PaymentHold?,
    ) = WalletView(status, chains = emptyList(), totals = emptyList(), fiat = null, hold = hold, access = access)

    private companion object {
        /** Chains read from Circle at once for one wallet: a wallet is on a handful, this only caps an odd one. */
        const val PARALLEL_READS = 4
    }
}
