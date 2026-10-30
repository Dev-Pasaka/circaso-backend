package com.circaso.wallet.domain.usecases

import com.circaso.integrations.api.ExchangeRates
import com.circaso.integrations.api.StablecoinCustody
import com.circaso.shared.Resource
import com.circaso.wallet.domain.models.ChainHoldings
import com.circaso.wallet.domain.models.ChainWallet
import com.circaso.wallet.domain.models.ChainWalletState
import com.circaso.wallet.domain.models.CircleUser
import com.circaso.wallet.domain.models.WalletQuery
import com.circaso.wallet.domain.models.WalletStatus
import com.circaso.wallet.domain.models.WalletView
import com.circaso.wallet.domain.ports.ActiveWallets
import com.circaso.wallet.domain.ports.AddingChains
import com.circaso.wallet.domain.repositories.WalletRepository
import com.circaso.wallet.domain.support.HoldingsReader
import com.circaso.wallet.domain.support.Valuation
import com.circaso.wallet.domain.support.WalletSync

/**
 * W1 `GET /wallet`: the wallet's status, each chain it is on with the assets held there, the totals over all chains
 * and, with `currency`, their worth in that currency. While the wallet is being set up or a chain added, Circle's
 * wallet list is read first, so a wallet the user just finished in the SDK shows at once.
 *
 * The fiat part is never an error: no rate (an unknown currency, the source down too long) leaves it out. A read
 * marks the user active ([ActiveWallets]) for `wallet.reconcile-active`.
 */
class GetWallet(
    private val wallets: WalletRepository,
    private val sync: WalletSync,
    private val adding: AddingChains,
    private val holdings: HoldingsReader,
    private val rates: ExchangeRates,
    private val custody: StablecoinCustody,
    private val active: ActiveWallets,
) {
    suspend operator fun invoke(query: WalletQuery): Resource<WalletView> {
        val user = wallets.circleUser(query.userId) ?: return Resource.success(empty(WalletStatus.NONE))
        // The user is looking: the light reconciliation pass checks their wallet for a lost webhook for a while.
        active.touch(query.userId)
        val open = current(user).filter { it.state != ChainWalletState.CLOSED }
        if (open.isEmpty()) return Resource.success(empty(WalletStatus.SETTING_UP))

        val chains = mutableListOf<ChainHoldings>()
        for (wallet in open) {
            when (val read = holdings.cached(user, wallet)) {
                is Resource.Success -> chains += ChainHoldings(wallet, read.data, receives(wallet.blockchain))
                is Resource.Error -> return read
            }
        }
        return Resource.success(view(chains, query.currency))
    }

    /** Our rows, after asking Circle when something is still on its way; Circle being down leaves our rows. */
    private suspend fun current(user: CircleUser): List<ChainWallet> {
        val known = wallets.wallets(user.userId)
        if (known.isNotEmpty() && adding.list(user.userId).isEmpty()) return known
        return (sync.sync(user) as? Resource.Success)?.data ?: known
    }

    private suspend fun view(chains: List<ChainHoldings>, currency: String?): WalletView {
        val rate = currency?.let { rates.usdRate(it) }
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
            // TODO(WS4): the payment hold after a support restore (payment_holds, decision 2026-10-28).
            hold = null,
        )
    }

    private fun receives(blockchain: String): Boolean = custody.supported.find(blockchain)?.receiveEnabled == true

    private fun empty(status: WalletStatus) =
        WalletView(status, chains = emptyList(), totals = emptyList(), fiat = null, hold = null)
}
