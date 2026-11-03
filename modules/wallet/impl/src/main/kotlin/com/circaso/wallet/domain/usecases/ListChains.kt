package com.circaso.wallet.domain.usecases

import com.circaso.integrations.api.StablecoinCustody
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.api.WalletId
import com.circaso.wallet.domain.models.ChainOption
import com.circaso.wallet.domain.models.ChainStatus
import com.circaso.wallet.domain.models.ChainWallet
import com.circaso.wallet.domain.models.ChainWalletState
import com.circaso.wallet.domain.ports.AddingChains
import com.circaso.wallet.domain.repositories.WalletRepository
import com.circaso.wallet.domain.support.Holders
import com.circaso.wallet.domain.support.WalletSync

/**
 * W12 `GET /wallet/chains`: every supported chain (server configuration; decision 2026-10-28), the assets offered on
 * it, which one a new wallet starts on, and whether this user's wallet is on it, being added, or could be. A chain
 * retired from receiving is listed only to a wallet that is on it, marked so.
 */
class ListChains(
    private val wallets: WalletRepository,
    private val sync: WalletSync,
    private val adding: AddingChains,
    private val custody: StablecoinCustody,
    private val holders: Holders,
) {
    /** [walletId] null lists for the caller's own wallet; a group wallet's for any of its members. */
    suspend operator fun invoke(caller: UserId, walletId: WalletId? = null): Resource<List<ChainOption>> {
        val userId =
            when (val access = holders.view(caller, walletId)) {
                is Resource.Success -> access.data.owner.holder
                is Resource.Error -> return access
            }
        val enabled = enabledChains(userId)
        val pending = adding.list(userId)
        val supported = custody.supported
        val options =
            supported.chains
                .filter { it.receiveEnabled || it.blockchain in enabled }
                .map { chain ->
                    ChainOption(
                        blockchain = chain.blockchain,
                        assets = chain.assets.map { it.symbol },
                        isDefault = chain.blockchain == supported.default,
                        status =
                            when (chain.blockchain) {
                                in enabled -> ChainStatus.ENABLED
                                in pending -> ChainStatus.ADDING
                                else -> ChainStatus.AVAILABLE
                            },
                        receiveEnabled = chain.receiveEnabled,
                    )
                }
        return Resource.success(options)
    }

    /** The chains the wallet is on, asking Circle first while one is being added. */
    private suspend fun enabledChains(userId: UserId): Set<String> {
        val user = wallets.circleUser(userId) ?: return emptySet()
        val known = wallets.wallets(userId)
        val current =
            if (adding.list(userId).isEmpty()) known else (sync.sync(user) as? Resource.Success)?.data ?: known
        return current.filter { it.isOpen() }.map { it.blockchain }.toSet()
    }

    private fun ChainWallet.isOpen() = state != ChainWalletState.CLOSED
}
