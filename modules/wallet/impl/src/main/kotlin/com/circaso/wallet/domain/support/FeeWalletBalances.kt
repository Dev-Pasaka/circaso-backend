package com.circaso.wallet.domain.support

import com.circaso.integrations.api.CustodyAsset
import com.circaso.integrations.api.TreasuryBalance
import com.circaso.integrations.api.TreasuryCustody
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.wallet.domain.models.FeeWallet
import com.circaso.wallet.domain.models.FeeWalletNetwork
import com.circaso.wallet.domain.models.FeeWalletStatus
import com.circaso.wallet.domain.repositories.FeeWalletRepository
import java.math.BigInteger

/** A coin a fee wallet holds, in minor units. */
data class FeeBalance(val symbol: String, val amountMinor: BigInteger, val decimals: Int)

/**
 * One chain's fee wallet as staff see it. [balancesKnown] false: Circle couldn't be asked (no wallet behind the
 * address, or Circle is down), so [balances] is empty and [nativeBalance] null rather than a zero nobody measured.
 * [withdrawable] false comes with [unavailable], which says why.
 */
data class FeeWalletView(
    val wallet: FeeWallet,
    val balances: List<FeeBalance>,
    val nativeBalance: FeeBalance?,
    val balancesKnown: Boolean,
    val withdrawable: Boolean,
    val unavailable: String?,
)

data class FeeWalletsView(
    val network: Network,
    val status: FeeWalletStatus,
    val reason: String?,
    val wallets: List<FeeWalletView>,
    val destinationDelayHours: Int,
)

/** A network's fee wallets with what they hold now, read from Circle on each call: a staff page, not a hot path. */
class FeeWalletBalances(
    private val treasury: TreasuryCustody?,
    private val networks: Networks,
    private val repository: FeeWalletRepository,
) {
    suspend fun view(network: Network): FeeWalletsView {
        val state: FeeWalletNetwork? = repository.network(network)
        val settings = networks.settings(network)
        val order = settings.chains.map { it.blockchain }
        val wallets =
            repository
                .wallets(network)
                .sortedBy { order.indexOf(it.blockchain).let { at -> if (at < 0) Int.MAX_VALUE else at } }
        val offered = settings.chains.associate { it.blockchain to it.assets }
        return FeeWalletsView(
            network = network,
            status = state?.status ?: FeeWalletStatus.NOT_CREATED,
            reason = if (state == null) "The fee wallets haven't been checked on this server yet." else state.reason,
            wallets = wallets.map { viewOf(it, offered[it.blockchain].orEmpty()) },
            destinationDelayHours = repository.destinationDelayHours(),
        )
    }

    /** The coins offered on [blockchain], as the network's settings have them now. */
    suspend fun offered(network: Network, blockchain: String): List<CustodyAsset> =
        networks
            .settings(network)
            .chains
            .firstOrNull { it.blockchain == blockchain }
            ?.assets
            .orEmpty()

    /** Why nothing can be withdrawn from [wallet], or null when it can. */
    fun unavailable(wallet: FeeWallet): String? {
        val custody = treasury ?: return "this server's custody provider isn't Circle."
        return when {
            wallet.circleWalletId == null -> {
                "the fee address on ${wallet.blockchain} isn't a wallet this server can reach at Circle."
            }

            !custody.canSign(wallet.network) -> {
                FeeWalletSetup.noSecret(wallet.network)
            }

            else -> {
                null
            }
        }
    }

    /** What the wallet holds at Circle; an error when it has no Circle wallet or Circle can't be asked. */
    suspend fun held(wallet: FeeWallet): Resource<List<TreasuryBalance>>? {
        val custody = treasury ?: return null
        val walletId = wallet.circleWalletId ?: return null
        if (!custody.canRead(wallet.network)) return null
        return custody.balances(wallet.network, walletId)
    }

    private suspend fun viewOf(wallet: FeeWallet, offered: List<CustodyAsset>): FeeWalletView {
        val unavailable = unavailable(wallet)
        val held = (held(wallet) as? Resource.Success)?.data
        if (held == null) {
            return FeeWalletView(wallet, emptyList(), null, false, unavailable == null, unavailable)
        }
        val native = held.firstOrNull { it.native }
        val tokens = held.filterNot { it.native }
        // Every offered coin shows, at zero when none was collected yet; then whatever else arrived.
        val offeredLines =
            offered.filter { it.tokenAddress.isNotBlank() }.map { asset ->
                val line = tokens.firstOrNull { sameToken(it.tokenAddress, asset.tokenAddress) }
                FeeBalance(asset.symbol, line?.amountMinor ?: BigInteger.ZERO, asset.decimals)
            }
        val others =
            tokens
                .filter { line -> offered.none { sameToken(it.tokenAddress, line.tokenAddress) } }
                .filter { it.amountMinor.signum() > 0 }
                .map { FeeBalance(it.symbol, it.amountMinor, it.decimals) }
        return FeeWalletView(
            wallet = wallet,
            balances = offeredLines + others,
            nativeBalance =
                FeeBalance(
                    symbol = FeeChains.nativeSymbol(wallet.blockchain),
                    amountMinor = native?.amountMinor ?: BigInteger.ZERO,
                    decimals = native?.decimals ?: FeeChains.NATIVE_DECIMALS,
                ),
            balancesKnown = true,
            withdrawable = unavailable == null,
            unavailable = unavailable,
        )
    }

    companion object {
        /** EVM contract addresses compare in any case. */
        fun sameToken(a: String, b: String): Boolean = Addresses.normalise(a.trim()) == Addresses.normalise(b.trim())
    }
}
