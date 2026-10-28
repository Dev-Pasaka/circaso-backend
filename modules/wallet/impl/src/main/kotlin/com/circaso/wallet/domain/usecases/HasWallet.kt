package com.circaso.wallet.domain.usecases

import com.circaso.shared.UserId
import com.circaso.wallet.domain.models.ChainWalletState
import com.circaso.wallet.domain.repositories.WalletRepository

/** `Wallets.hasWallet`: whether the user's wallet is set up on at least one chain, from our rows only. */
class HasWallet(private val wallets: WalletRepository) {
    suspend operator fun invoke(userId: UserId): Boolean =
        wallets.wallets(userId).any { it.state == ChainWalletState.ACTIVE }
}
