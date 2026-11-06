package com.circaso.wallet.data.repositories

import com.circaso.wallet.domain.models.CoinIcon
import com.circaso.wallet.domain.repositories.CoinIconRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The Web3 Icons mono set vendored into `coin-icons/` by CircasoApp's `scripts/coin-icons/generate.py`: one cleaned
 * SVG per symbol and `symbols.txt`, the index. Only a symbol in the index is ever read, and each is read once.
 */
class BundledCoinIcons(private val root: String = "coin-icons") : CoinIconRepository {
    private val loader = BundledCoinIcons::class.java.classLoader

    private val symbols: Set<String> by lazy {
        loader
            .getResourceAsStream("$root/symbols.txt")
            ?.bufferedReader()
            ?.useLines { lines -> lines.map(String::trim).filter(String::isNotEmpty).toSet() }
            .orEmpty()
    }

    private val icons = ConcurrentHashMap<String, CoinIcon>()

    override suspend fun find(symbol: String): CoinIcon? {
        icons[symbol]?.let { return it }
        // The index and the file come off the jar once each, away from the request threads.
        val svg =
            withContext(Dispatchers.IO) {
                if (symbol !in
                    symbols
                ) {
                    null
                } else {
                    loader.getResourceAsStream("$root/$symbol.svg")?.use { it.readBytes() }
                }
            } ?: return null
        val hash = MessageDigest.getInstance("SHA-256").digest(svg).joinToString("") { "%02x".format(it) }
        return CoinIcon(symbol, svg, "\"${hash.take(32)}\"").also { icons[symbol] = it }
    }
}
