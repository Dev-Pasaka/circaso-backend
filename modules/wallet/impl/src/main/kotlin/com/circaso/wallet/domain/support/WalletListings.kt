package com.circaso.wallet.domain.support

import com.circaso.shared.UserId
import com.circaso.wallet.domain.models.ChainWalletState
import com.circaso.wallet.domain.models.WalletListing
import com.circaso.wallet.domain.models.WalletOwner
import com.circaso.wallet.domain.models.WalletStatus
import com.circaso.wallet.domain.repositories.WalletRepository

/**
 * A wallet as a list shows it (W15, W16, `Wallets.groupWallet`), from our rows only, never Circle: `ACTIVE` once a
 * chain is recorded, `SETTING_UP` while its Circle user exists without one, `NONE` before that.
 */
class WalletListings(private val wallets: WalletRepository, private val payees: Payees) {
    suspend fun of(owner: WalletOwner, caller: UserId?): WalletListing {
        val holder = owner.holder
        val recorded = wallets.wallets(holder)
        val status =
            when {
                recorded.any { it.state == ChainWalletState.ACTIVE } -> WalletStatus.ACTIVE
                recorded.isNotEmpty() || wallets.circleUser(holder) != null -> WalletStatus.SETTING_UP
                owner is WalletOwner.Group -> WalletStatus.SETTING_UP
                else -> WalletStatus.NONE
            }
        val chains = payees.receiving(holder).open.map { it.blockchain }
        return WalletListing(owner, status, canSign = caller != null && owner.signerId == caller, blockchains = chains)
    }
}
