package com.circaso.wallet.domain.support

import com.circaso.integrations.api.CryptoPrices
import com.circaso.integrations.api.StablecoinCustody
import com.circaso.wallet.domain.models.ChainHoldings
import com.circaso.wallet.domain.models.Holding
import java.math.BigDecimal

/**
 * Puts a US dollar price on each holding (decision 2026-11-06), for showing it in the person's own money and for
 * typing an amount of it in that money. A payment in a coin that isn't a dollar stablecoin reads the same feed for its
 * fee and limits (decision 2026-11-07, [PaymentCoins]); the coins trusted are the same ([CoinTrust]).
 *
 * A symbol alone is not trusted ([CoinTrust]): a dollar stablecoin we offer is one dollar; a chain's own coin and an
 * asset we offer on that chain are priced by their symbol; any other token only when its contract is a known one.
 * Everything else stays unpriced and the fiat total says it is partial.
 *
 * The feed is asked only when a coin we would price is held (above zero), so a wallet of stablecoins never calls it.
 */
class HoldingPrices(private val prices: CryptoPrices, private val custody: StablecoinCustody) {
    suspend fun price(chains: List<ChainHoldings>): List<ChainHoldings> {
        val keys = chains.map { chain -> chain.holdings.map { priceKey(chain.wallet.blockchain, it) } }
        // Only what is held: a zero line (an offered coin not held) needs no price, so it never calls the feed.
        val wanted =
            chains
                .zip(keys)
                .flatMap { (chain, chainKeys) -> chain.holdings.zip(chainKeys) }
                .filter { (holding, key) -> key != null && key != DOLLAR && holding.amountMinor.signum() > 0 }
                .mapNotNull { it.second }
                .toSet()
        val found = if (wanted.isEmpty()) emptyMap() else prices.usdPrices(wanted)
        return chains.zip(keys) { chain, chainKeys ->
            chain.copy(
                holdings =
                    chain.holdings.mapIndexed { i, holding ->
                        val price =
                            when (val key = chainKeys[i]) {
                                null -> null
                                DOLLAR -> BigDecimal.ONE
                                else -> found[key]
                            }
                        holding.copy(usdPrice = price)
                    },
            )
        }
    }

    /** The symbol to price [holding] by, [DOLLAR] for a dollar stablecoin, or null when it mustn't be priced. */
    private fun priceKey(blockchain: String, holding: Holding): String? =
        CoinTrust.priceKey(custody.supported, blockchain, holding.symbol, holding.tokenAddress, holding.usdPegged)

    companion object {
        private const val DOLLAR = CoinTrust.DOLLAR

        /** The tokens priced by contract; see [CoinTrust.KNOWN_TOKENS]. */
        val KNOWN_TOKENS: Map<String, String> get() = CoinTrust.KNOWN_TOKENS
    }
}
