package com.circaso.wallet.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.repositories.WalletOwnerRepository
import com.circaso.wallet.domain.repositories.WalletRepository
import com.circaso.wallet.domain.support.HeldFunds
import com.circaso.wallet.domain.support.HoldingsReader
import com.circaso.wallet.domain.support.WalletSync

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

    private val funds = HeldFunds(wallets, sync, holdings)

    private suspend fun check(holder: UserId): Resource<Boolean> = funds.holdsMoney(holder)
}
