package com.circaso.wallet.domain.support

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.domain.models.ChainWallet
import com.circaso.wallet.domain.models.ChainWalletState
import com.circaso.wallet.domain.models.Holding
import com.circaso.wallet.domain.repositories.WalletRepository
import java.math.BigDecimal

/**
 * Whether a wallet still holds money, read from Circle, never the cache (the deletion guard's rule, decision
 * 2026-10-28): any amount of an asset that isn't a US dollar stablecoin, or a cent or more of them added up over every
 * chain. Shared by the deletion guard and a space's deletion (Spaces phase 3).
 */
class HeldFunds(
    private val wallets: WalletRepository,
    private val sync: WalletSync,
    private val holdings: HoldingsReader,
) {
    /** Whether the wallet filed under [holder] still holds money; an error when Circle can't say. */
    suspend fun holdsMoney(holder: UserId): Resource<Boolean> {
        val user = wallets.circleUser(holder) ?: return Resource.success(false)
        val synced =
            when (val read = sync.syncAll(user)) {
                is Resource.Success -> read.data
                is Resource.Error -> return read
            }
        val held = mutableListOf<Holding>()
        for (wallet in toCheck(synced)) {
            when (val read = holdings.fresh(user, wallet)) {
                is Resource.Success -> held += read.data
                is Resource.Error -> return read
            }
        }
        if (held.any { !it.usdPegged && it.amountMinor.signum() > 0 }) return Resource.success(true)
        return Resource.success(dollars(held) >= CENT)
    }

    /** Circle's wallets, and any open row of ours Circle didn't list, each once. */
    private fun toCheck(synced: WalletSync.Synced): List<ChainWallet> {
        val atCircle = synced.atCircle.map { it.circleWalletId }.toSet()
        val onlyOurs = synced.recorded.filter { it.state != ChainWalletState.CLOSED && it.circleWalletId !in atCircle }
        return synced.atCircle + onlyOurs
    }

    /**
     * Every US dollar stablecoin held, on every chain, in dollars: below a cent in all it can't be sent back out
     * economically. TODO(decision: the dust threshold for assets that aren't US dollar stablecoins) any amount of
     * another asset refuses.
     */
    private fun dollars(held: List<Holding>): BigDecimal =
        held.filter { it.usdPegged }.fold(BigDecimal.ZERO) { sum, it -> sum + BigDecimal(it.amountMinor, it.decimals) }

    private companion object {
        val CENT: BigDecimal = BigDecimal("0.01")
    }
}
