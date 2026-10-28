package com.circaso.wallet.domain.usecases

import com.circaso.integrations.api.StablecoinCustody
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.domain.models.ChainOption
import com.circaso.wallet.domain.models.ChainStatus
import com.circaso.wallet.domain.models.ChainWallet
import com.circaso.wallet.domain.models.ChainWalletState
import com.circaso.wallet.domain.ports.AddingChains
import com.circaso.wallet.domain.repositories.WalletRepository
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
) {
    suspend operator fun invoke(userId: UserId): Resource<List<ChainOption>> {
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
