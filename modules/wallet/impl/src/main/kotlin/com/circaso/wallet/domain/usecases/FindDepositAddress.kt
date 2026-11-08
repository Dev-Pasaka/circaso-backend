package com.circaso.wallet.domain.usecases

import com.circaso.shared.UserId
import com.circaso.wallet.domain.models.ChainWalletState
import com.circaso.wallet.domain.repositories.WalletRepository

/**
 * `Wallets.depositAddress`: where the person's own wallet is paid on one chain, from our rows only, for an address
 * share link (decision 2026-11-06). Only a wallet that is set up there counts.
 */
class FindDepositAddress(private val wallets: WalletRepository) {
    suspend operator fun invoke(userId: UserId, blockchain: String): String? =
        wallets
            .wallets(userId)
            .firstOrNull { it.blockchain.equals(blockchain, ignoreCase = true) && it.state == ChainWalletState.ACTIVE }
            ?.address
}
