package com.circaso.wallet.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.ChainWalletState
import com.circaso.wallet.domain.models.Holding
import com.circaso.wallet.domain.repositories.WalletRepository
import com.circaso.wallet.domain.support.HoldingsReader
import com.circaso.wallet.domain.support.WalletSync
import java.math.BigInteger

/**
 * Identity's `DeletionGuard` (I14): an account may be deleted only while its wallet holds less than 0.01 of every US
 * dollar stablecoin on every chain. The answer reaches the app as `wallet.funds_remaining` (decision 2026-10-28).
 *
 * It decides about money, so it reads Circle, never the cache, after asking Circle for wallets we may not have
 * recorded yet; when Circle can't answer, deletion is refused with `wallet.custody_unavailable` rather than allowed
 * on a guess.
 */
class CheckFundsBeforeDeletion(
    private val wallets: WalletRepository,
    private val sync: WalletSync,
    private val holdings: HoldingsReader,
) {
    suspend operator fun invoke(userId: UserId): Resource<Unit> {
        val user = wallets.circleUser(userId) ?: return Resource.success(Unit)
        val all =
            when (val synced = sync.sync(user)) {
                is Resource.Success -> synced.data
                is Resource.Error -> return synced
            }
        for (wallet in all.filter { it.state != ChainWalletState.CLOSED }) {
            val held =
                when (val read = holdings.fresh(user, wallet)) {
                    is Resource.Success -> read.data
                    is Resource.Error -> return read
                }
            if (held.any { it.counts() }) return Resource.error(WalletError.FundsRemaining)
        }
        return Resource.success(Unit)
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
