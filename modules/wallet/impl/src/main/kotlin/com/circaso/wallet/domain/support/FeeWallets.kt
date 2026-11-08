package com.circaso.wallet.domain.support

import com.circaso.wallet.domain.ports.FeeConfig

/** Circaso's own fee wallets (the configured address per chain), so staff see them as Circaso's, never as outsiders. */
class FeeWallets(private val config: FeeConfig) {
    fun isFeeWallet(blockchain: String, address: String): Boolean {
        val fee = config.feeAddress(blockchain)?.let(Addresses::normalise) ?: return false
        return address.isNotEmpty() && Addresses.normalise(address) == fee
    }
}
