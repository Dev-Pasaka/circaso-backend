package com.circaso.wallet.domain.support

import com.circaso.integrations.api.AssetBalance
import com.circaso.integrations.api.StablecoinCustody
import com.circaso.shared.Resource
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.ChainWallet
import com.circaso.wallet.domain.models.CircleUser
import com.circaso.wallet.domain.models.Holding
import com.circaso.wallet.domain.ports.BalanceCache
import com.circaso.wallet.domain.ports.WalletWarnings

/**
 * What a wallet holds on one chain, from Circle, which stays the source of truth for balances (Architecture
 * principle 11). The wallet screen reads through the 15-second cache; the deletion guard always asks Circle.
 *
 * A chain is never taken out of configuration while any wallet holds funds on it; it is retired by disabling receive
 * instead, and stays readable (decision 2026-10-28). A chain that is unconfigured anyway stays fail-closed: the wallet
 * screen shows it with nothing on it, and the deletion guard refuses with `wallet.custody_unavailable`.
 */
class HoldingsReader(
    private val custody: StablecoinCustody,
    private val cache: BalanceCache,
    private val warnings: WalletWarnings,
) {
    /**
     * The cached holdings or Circle's. A chain no longer supported (taken out of the configuration) shows with
     * nothing on it rather than failing the whole wallet.
     */
    suspend fun cached(user: CircleUser, wallet: ChainWallet): Resource<List<Holding>> {
        if (!isConfigured(wallet)) return Resource.success(emptyList())
        cache.get(wallet.circleWalletId)?.let { return Resource.success(it) }
        val read = fresh(user, wallet)
        if (read is Resource.Success) cache.put(wallet.circleWalletId, read.data)
        return read
    }

    /**
     * Circle's answer now, never the cache: for decisions about money (the deletion guard). A chain no longer
     * configured can't be read, which is `wallet.custody_unavailable`: the guard refuses rather than guess.
     */
    suspend fun fresh(user: CircleUser, wallet: ChainWallet): Resource<List<Holding>> {
        if (!isConfigured(wallet)) return Resource.error(WalletError.CustodyUnavailable)
        return when (
            val read =
                custody
                    .balances(user.circleUserId.toString(), wallet.circleWalletId, wallet.blockchain)
                    .orCustodyUnavailable()
        ) {
            is Resource.Success -> Resource.success(read.data.map { it.toHolding() })
            is Resource.Error -> read
        }
    }

    private fun isConfigured(wallet: ChainWallet): Boolean {
        if (custody.supported.find(wallet.blockchain) != null) return true
        warnings.chainNotConfigured(wallet.blockchain, wallet.circleWalletId)
        return false
    }

    private fun AssetBalance.toHolding() =
        Holding(
            symbol = asset.symbol,
            tokenAddress = asset.tokenAddress,
            decimals = asset.decimals,
            usdPegged = asset.usdPegged,
            amountMinor = amountMinor,
        )
}
