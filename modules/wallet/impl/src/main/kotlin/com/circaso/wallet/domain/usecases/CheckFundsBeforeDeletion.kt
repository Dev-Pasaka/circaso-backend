package com.circaso.wallet.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.ChainWallet
import com.circaso.wallet.domain.models.ChainWalletState
import com.circaso.wallet.domain.models.Holding
import com.circaso.wallet.domain.repositories.WalletOwnerRepository
import com.circaso.wallet.domain.repositories.WalletRepository
import com.circaso.wallet.domain.support.HoldingsReader
import com.circaso.wallet.domain.support.WalletSync
import java.math.BigDecimal

/**
 * Identity's `DeletionGuard` (I14): an account may be deleted only while the US dollar stablecoins it holds, added up
 * over every chain, come to less than 0.01, and it holds nothing of any other asset (decision 2026-10-28: the
 * threshold is the sum, so 0.009 USDC on each of four chains refuses). The answer reaches the app as
 * `wallet.funds_remaining`.
 *
 * It decides about money, so it reads Circle, never the cache, for every wallet Circle lists for the user as well as
 * every one we recorded: Circle can hold a wallet our rows never will (a second one on a chain). When Circle can't
 * answer, or a wallet sits on a chain no longer configured, deletion is refused with `wallet.custody_unavailable`
 * rather than allowed on a guess. A chain is retired by disabling receive, never removed while funds are on it
 * (decision 2026-10-28), so a retired chain is still read here; one unconfigured anyway stays fail-closed.
 */
class CheckFundsBeforeDeletion(
    private val wallets: WalletRepository,
    private val sync: WalletSync,
    private val holdings: HoldingsReader,
    private val owners: WalletOwnerRepository,
) {
    /**
     * The person's own wallet, then every group wallet they sign for (decision 2026-11-03): only they can move a
     * group's money, so deleting them would lock it; they empty it first (`wallet.group_funds_remaining`).
     */
    suspend operator fun invoke(userId: UserId): Resource<Unit> {
        when (val own = check(userId)) {
            is Resource.Error -> return own
            is Resource.Success -> if (own.data) return Resource.error(WalletError.FundsRemaining)
        }
        for (group in owners.bySigner(userId)) {
            when (val held = check(group.holder)) {
                is Resource.Error -> return held
                is Resource.Success -> if (held.data) return Resource.error(WalletError.GroupFundsRemaining)
            }
        }
        return Resource.success(Unit)
    }

    /** Whether the wallet filed under [holder] still holds money; an error when Circle can't say. */
    private suspend fun check(holder: UserId): Resource<Boolean> {
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
