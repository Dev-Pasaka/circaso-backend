package com.circaso.wallet.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.ChainWallet
import com.circaso.wallet.domain.models.ChainWalletState
import com.circaso.wallet.domain.models.Holding
import com.circaso.wallet.domain.repositories.WalletRepository
import com.circaso.wallet.domain.support.HoldingsReader
import com.circaso.wallet.domain.support.WalletSync
import java.math.BigInteger

/**
 * Identity's `DeletionGuard` (I14): an account may be deleted only while its wallet holds less than 0.01 of every US
 * dollar stablecoin and nothing of any other asset, on every chain. The answer reaches the app as
 * `wallet.funds_remaining` (decision 2026-10-28).
 *
 * It decides about money, so it reads Circle, never the cache, for every wallet Circle lists for the user as well as
 * every one we recorded: Circle can hold a wallet our rows never will (a second one on a chain). When Circle can't
 * answer, or a wallet sits on a chain no longer configured, deletion is refused with `wallet.custody_unavailable`
 * rather than allowed on a guess.
 *
 * TODO(decision: a chain taken out of configuration while wallets are on it) such a wallet blocks deletion for now.
 */
class CheckFundsBeforeDeletion(
    private val wallets: WalletRepository,
    private val sync: WalletSync,
    private val holdings: HoldingsReader,
) {
    suspend operator fun invoke(userId: UserId): Resource<Unit> {
        val user = wallets.circleUser(userId) ?: return Resource.success(Unit)
        val synced =
            when (val read = sync.syncAll(user)) {
                is Resource.Success -> read.data
                is Resource.Error -> return read
            }
        for (wallet in toCheck(synced)) {
            val held =
                when (val read = holdings.fresh(user, wallet)) {
                    is Resource.Success -> read.data
                    is Resource.Error -> return read
                }
            if (held.any { it.counts() }) return Resource.error(WalletError.FundsRemaining)
        }
        return Resource.success(Unit)
    }

    /** Circle's wallets, and any open row of ours Circle didn't list, each once. */
    private fun toCheck(synced: WalletSync.Synced): List<ChainWallet> {
        val atCircle = synced.atCircle.map { it.circleWalletId }.toSet()
        val onlyOurs = synced.recorded.filter { it.state != ChainWalletState.CLOSED && it.circleWalletId !in atCircle }
        return synced.atCircle + onlyOurs
    }

    /**
     * A US dollar stablecoin counts from 0.01 (a cent can't be sent back out economically below that).
     * TODO(decision: the dust threshold for assets that aren't US dollar stablecoins) any amount of one counts.
     */
    private fun Holding.counts(): Boolean {
        if (!usdPegged) return amountMinor.signum() > 0
        val cent = if (decimals >= 2) BigInteger.TEN.pow(decimals - 2) else BigInteger.ONE
        return amountMinor >= cent
    }
}
