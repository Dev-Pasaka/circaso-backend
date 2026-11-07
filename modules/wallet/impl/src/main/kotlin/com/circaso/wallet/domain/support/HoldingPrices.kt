package com.circaso.wallet.domain.support

import com.circaso.integrations.api.CryptoPrices
import com.circaso.integrations.api.StablecoinCustody
import com.circaso.wallet.domain.models.ChainHoldings
import com.circaso.wallet.domain.models.Holding
import java.math.BigDecimal

/**
 * Puts a US dollar price on each holding (decision 2026-11-06), for showing it in the person's own money and for
 * typing an amount of it in that money. Prices are display only: payments and fees never read them.
 *
 * A symbol alone is not trusted. Anyone can send a wallet a token they named `ETH`, so a coin is priced only when we
 * know it is the real one: a dollar stablecoin we offer is one dollar; a chain's own coin (no contract) and an asset
 * we offer on that chain are priced by their symbol; any other token only when its contract is one of
 * [KNOWN_TOKENS], and then by the symbol we know it as, never by the one it calls itself. Everything else stays
 * unpriced and the fiat total says it is partial.
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
    private fun priceKey(blockchain: String, holding: Holding): String? {
        val address = holding.tokenAddress.trim()
        val offered =
            custody.supported.find(blockchain)?.assets?.any {
                it.symbol == holding.symbol && sameAddress(it.tokenAddress, address)
            } == true
        return when {
            holding.usdPegged && offered -> DOLLAR
            address.isEmpty() || offered -> holding.symbol
            else -> KNOWN_TOKENS[address.lowercase()] ?: KNOWN_TOKENS[address]
        }
    }

    /** EVM addresses compare without case (checksums); a Solana mint is case-sensitive. */
    private fun sameAddress(a: String, b: String): Boolean =
        if (a.startsWith("0x") && b.startsWith("0x")) a.equals(b, ignoreCase = true) else a == b

    companion object {
        private const val DOLLAR = "\$USD"

        /**
         * Tokens priced by contract, mainnet and testnet, to the symbol the feed knows them by. EVM addresses in lower
         * case; Solana mints as written. Add a token here only from its issuer's own list of addresses.
         */
        val KNOWN_TOKENS: Map<String, String> =
            mapOf(
                // WETH: Base and OP (and Base Sepolia), Ethereum, Polygon.
                "0x4200000000000000000000000000000000000006" to "WETH",
                "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2" to "WETH",
                "0x7ceb23fd6bc0add59e62ac25578270cff1b9f619" to "WETH",
                // WBTC: Ethereum, Polygon.
                "0x2260fac5e5542a773aa44fbcfedf7c193bc2c599" to "WBTC",
                "0x1bfd67037b42cf73acf2047067bd4f2c47d9bfd6" to "WBTC",
                // cbBTC: the same address on Base and Ethereum.
                "0xcbb7c0000ab88b473b1f5afd9ef808440eed33bf" to "CBBTC",
                // EURC (Circle): Ethereum, Base, Avalanche, Solana, then Sepolia and Base Sepolia.
                "0x1abaea1f7c830bd89acc67ec4af516284b1bc33c" to "EURC",
                "0x60a3e35cc302bfa44cb288bc5a4f316fdb1adb42" to "EURC",
                "0xc891eb4cbdeff6e073e859e987815ed1505c2acd" to "EURC",
                "HzwqbKZw8HxMN6bF2yFZNrht3c2iXXzpKcFu7uBEDKtr" to "EURC",
                "0x08210f9170f89ab7658f0b5e3ff39b0e03c594d4" to "EURC",
                "0x808456652fdb597867f38412077a9182bf77359f" to "EURC",
            )
    }
}
