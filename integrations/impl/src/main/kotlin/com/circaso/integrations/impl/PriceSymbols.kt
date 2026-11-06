package com.circaso.integrations.impl

import java.math.BigDecimal

/**
 * The coins a price is looked up for, shared by every [com.circaso.integrations.api.CryptoPrices] implementation so
 * `log` and `fake` price the same symbols the real feed does.
 */
internal object PriceSymbols {
    /** US dollar stablecoins: one dollar each, never asked of a feed. */
    private val DOLLARS = setOf("USDC", "USDT")

    /**
     * Each symbol we hold or show, by its CoinGecko id (decision 2026-11-06). Testnet coins are priced as their
     * mainnet coin: a Sepolia ETH is shown at the price of ETH, so the screens look as they will on mainnet. MATIC is
     * POL since Polygon's migration; the wrapped coins have their own ids (they trade a hair off the coin).
     */
    val COINGECKO_IDS: Map<String, String> =
        mapOf(
            "ETH" to "ethereum",
            "WETH" to "weth",
            "POL" to "polygon-ecosystem-token",
            "MATIC" to "polygon-ecosystem-token",
            "SOL" to "solana",
            "AVAX" to "avalanche-2",
            "BTC" to "bitcoin",
            "WBTC" to "wrapped-bitcoin",
            "CBBTC" to "coinbase-wrapped-btc",
            "EURC" to "euro-coin",
        )

    /** `ETH-SEPOLIA`, `eth` and ` ETH ` are all `ETH`: Circle names testnet coins after their network. */
    fun normalise(symbol: String): String = symbol.substringBefore('-').trim().uppercase()

    fun isDollar(symbol: String): Boolean = normalise(symbol) in DOLLARS

    /**
     * The answer for [symbols] from a table of prices by normalised symbol: each asked symbol that has one, under the
     * symbol as asked, dollar stablecoins at one.
     */
    fun answer(symbols: Set<String>, table: (String) -> BigDecimal?): Map<String, BigDecimal> =
        symbols
            .mapNotNull { asked ->
                val symbol = normalise(asked)
                val price = if (symbol in DOLLARS) BigDecimal.ONE else table(symbol)
                price?.takeIf { it.signum() > 0 }?.let { asked to it }
            }.toMap()
}
