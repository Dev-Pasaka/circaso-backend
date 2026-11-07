package com.circaso.wallet.domain.support

import com.circaso.integrations.api.CryptoPrices
import com.circaso.integrations.api.StablecoinCustody
import com.circaso.integrations.api.SupportedChains
import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.Resource
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.Holding
import com.circaso.wallet.domain.models.RequestedAmount
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * Which coins are what they say they are (decision 2026-11-06, moved here 2026-11-07 so paying and pricing share it).
 * Anyone can send a wallet a token they named `ETH`, so a coin is trusted only when we know it is the real one: an
 * asset offered on the chain (by its contract), the chain's own coin (no contract), or a token whose contract is one
 * of [KNOWN_TOKENS], then known by the symbol we have for it, never by the one it calls itself. Only a trusted coin is
 * priced, paid, or put in the history.
 */
object CoinTrust {
    /** The price key of a US dollar stablecoin we offer: one dollar, without asking the feed. */
    const val DOLLAR = "\$USD"

    /**
     * The symbol to price a coin by ([DOLLAR] for an offered dollar stablecoin), or null when it isn't trusted.
     * [tokenAddress] is its contract on [blockchain], empty for the chain's own coin.
     */
    fun priceKey(
        supported: SupportedChains,
        blockchain: String,
        symbol: String,
        tokenAddress: String,
        usdPegged: Boolean,
    ): String? {
        val address = tokenAddress.trim()
        val offered =
            supported.find(blockchain)?.assets?.firstOrNull {
                it.symbol == symbol && sameAddress(it.tokenAddress, address)
            }
        return when {
            offered != null && (usdPegged || offered.usdPegged) -> DOLLAR
            offered != null || address.isEmpty() -> symbol
            else -> KNOWN_TOKENS[address.lowercase()] ?: KNOWN_TOKENS[address]
        }
    }

    /** Whether [holding] on [blockchain] is a coin we trust. */
    fun trusted(
        supported: SupportedChains,
        blockchain: String,
        holding: Holding,
    ): Boolean = priceKey(supported, blockchain, holding.symbol, holding.tokenAddress, holding.usdPegged) != null

    /**
     * Whether a coin Circle reported moving ([symbol] at [tokenAddress] on [blockchain]) is one we trust. A null
     * address (an update that doesn't say) is trusted only for an asset offered there under that symbol.
     */
    fun trusted(
        supported: SupportedChains,
        blockchain: String,
        symbol: String,
        tokenAddress: String?,
    ): Boolean {
        if (tokenAddress == null) return supported.find(blockchain)?.assets?.any { it.symbol == symbol } == true
        return priceKey(supported, blockchain, symbol, tokenAddress, usdPegged = false) != null
    }

    /** EVM addresses compare without case (checksums); a Solana mint is case-sensitive. */
    fun sameAddress(a: String, b: String): Boolean =
        if (a.startsWith("0x") && b.startsWith("0x")) a.equals(b, ignoreCase = true) else a == b

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
            // EURC, every address on Circle's list (developers.circle.com/stablecoins/eurc-contract-addresses,
            // read 2026-11-07). Mainnet: Ethereum, Base, Avalanche, Arc, Cronos, Plasma, World Chain, and Solana
            // (whose devnet mint is the same).
            "0x1abaea1f7c830bd89acc67ec4af516284b1bc33c" to "EURC",
            "0x60a3e35cc302bfa44cb288bc5a4f316fdb1adb42" to "EURC",
            "0xc891eb4cbdeff6e073e859e987815ed1505c2acd" to "EURC",
            "0xbef5f6d51cb62b58e6a8f77868681825c6fe21c1" to "EURC",
            "0xa6de01a2d62c6b5f3525d768f34d276652c554c8" to "EURC",
            "0x3ee196e78d4d4248b849b8e1c7f44c5457fafd2c" to "EURC",
            "0x1c60ba0a0ed1019e8eb035e6daf4155a5ce2380b" to "EURC",
            "HzwqbKZw8HxMN6bF2yFZNrht3c2iXXzpKcFu7uBEDKtr" to "EURC",
            // Testnets: Ethereum Sepolia, Base Sepolia, Avalanche Fuji, Arc, Cronos, Plasma, World Chain Sepolia.
            "0x08210f9170f89ab7658f0b5e3ff39b0e03c594d4" to "EURC",
            "0x808456652fdb597867f38412077a9182bf77359f" to "EURC",
            "0x5e44db7996c682e92a960b65ac713a54ad815c6b" to "EURC",
            "0x89b50855aa3be2f677cd6303cec089b5f319d72a" to "EURC",
            "0x31f7538adb53cf16350e6b0c89d03d91b7d12c46" to "EURC",
            "0x98afa0f93dd993b736399f9074edcebd1985a330" to "EURC",
            "0xe479eca5740ac65d6e1823bea2f1c08bc14e954f" to "EURC",
        )
}

/**
 * The coin a payment is in (decision 2026-11-07): the [asset], its contract on the chain ([tokenAddress], empty for
 * the chain's own coin), and what one whole coin is worth in US dollars ([usdPrice], one for a dollar stablecoin).
 * [heldMinor] is what the wallet holds of it when it was read from Circle to find it.
 *
 * Fees and limits are priced in micro-dollars; [usdMinor] and [minorOf] convert. For a 6-decimal coin at one dollar
 * (USDC, USDT) both are the identity, so their payments are priced exactly as before.
 */
data class PaymentCoin(
    val asset: Asset,
    val tokenAddress: String,
    val usdPrice: BigDecimal,
    val heldMinor: BigInteger? = null,
) {
    /** [amountMinor] of the coin in micro-dollars, half up (it is what a payment counts, not what is charged). */
    fun usdMinor(amountMinor: BigInteger): Long =
        BigDecimal(amountMinor)
            .multiply(usdPrice)
            .movePointRight(MICRO - asset.decimals)
            .setScale(0, RoundingMode.HALF_UP)
            .toBigInteger()
            .min(LONG_MAX)
            .toLong()

    /** [usdMinor] micro-dollars in the coin, rounded up to its minor unit, so the coin never covers less. */
    fun minorOf(usdMinor: Long): BigInteger {
        if (usdMinor <= 0) return BigInteger.ZERO
        return BigDecimal
            .valueOf(usdMinor)
            .movePointRight(asset.decimals - MICRO)
            .divide(usdPrice, 0, RoundingMode.CEILING)
            .toBigInteger()
    }

    /** The most of the coin worth at most [usdMinor] micro-dollars by [usdMinor]'s rounding (one payment's cap). */
    fun maxWithinUsd(usdMinor: Long): BigInteger {
        if (usdMinor < 0) return BigInteger.ZERO
        // usdMinor(a) rounds half up, so a fits while its exact value stays below usdMinor + 0.5.
        val bound =
            BigDecimal
                .valueOf(usdMinor)
                .add(HALF)
                .movePointRight(asset.decimals - MICRO)
                .divide(usdPrice, 0, RoundingMode.CEILING)
                .toBigInteger() - BigInteger.ONE
        return bound.max(BigInteger.ZERO)
    }

    override fun toString(): String = "PaymentCoin($asset)"

    companion object {
        /** Micro-dollars: the unit fees and limits are kept in. */
        const val MICRO = 6
        private val LONG_MAX: BigInteger = BigInteger.valueOf(Long.MAX_VALUE)
        private val HALF: BigDecimal = BigDecimal("0.5")

        /** A dollar stablecoin at one dollar; for amounts that are only ever USDC (a membership, a payout request). */
        fun dollar(asset: Asset, tokenAddress: String = "") = PaymentCoin(asset, tokenAddress, BigDecimal.ONE)
    }
}

/**
 * Finds the coin a payment names on the sender's chain (decision 2026-11-07: a person pays any coin they hold). An
 * asset offered on the chain is found in the configuration, as before, with no call to Circle; any other coin is read
 * from Circle now (never the cache) among what the wallet holds there. A coin the wallet doesn't hold above zero on
 * the chain is `wallet.asset_not_held`; one that isn't trusted ([CoinTrust]) or has no price right now (the feed off,
 * or it doesn't know the coin) is `wallet.asset_unpriced`, since its fee can't be worked out in dollars.
 */
class PaymentCoins(
    private val custody: StablecoinCustody,
    private val holdings: HoldingsReader,
    private val prices: CryptoPrices,
) {
    suspend fun find(sender: Sender, requested: RequestedAmount): Resource<Pair<PaymentCoin, Money>> {
        if (requested.minor?.let { it.signum() <= 0 } == true) return Resource.error(WalletError.AmountInvalid)
        if (requested.whole?.let { it.signum() <= 0 } == true) return Resource.error(WalletError.AmountInvalid)
        val chain = sender.wallet.blockchain
        val offered =
            custody.supported
                .find(chain)
                ?.assets
                ?.firstOrNull { it.symbol == requested.symbol }
        val coin =
            if (offered != null) {
                val asset = Asset(offered.symbol, offered.decimals)
                val price =
                    if (offered.usdPegged) {
                        BigDecimal.ONE
                    } else {
                        priceOf(offered.symbol) ?: return Resource.error(WalletError.AssetUnpriced)
                    }
                PaymentCoin(asset, offered.tokenAddress, price)
            } else {
                when (val found = held(sender, requested.symbol)) {
                    is Resource.Success -> found.data
                    is Resource.Error -> return found
                }
            }
        val minor = requested.minorIn(coin.asset.decimals)
        if (minor == null || minor.signum() <= 0) return Resource.error(WalletError.AmountInvalid)
        return Resource.success(coin to Money(minor, coin.asset))
    }

    /** A coin the wallet holds on the chain that isn't offered there: trusted, priced, above zero. */
    private suspend fun held(sender: Sender, symbol: String): Resource<PaymentCoin> {
        val chain = sender.wallet.blockchain
        val lines =
            when (val read = holdings.fresh(sender.user, sender.wallet)) {
                is Resource.Success -> read.data
                is Resource.Error -> return read
            }.filter { it.symbol == symbol && it.amountMinor.signum() > 0 }
        if (lines.isEmpty()) return Resource.error(WalletError.AssetNotHeld)
        val trusted =
            lines.firstNotNullOfOrNull { line ->
                CoinTrust
                    .priceKey(custody.supported, chain, line.symbol, line.tokenAddress, line.usdPegged)
                    ?.let { key -> line to key }
            } ?: return Resource.error(WalletError.AssetUnpriced)
        val (line, key) = trusted
        val price =
            if (key ==
                CoinTrust.DOLLAR
            ) {
                BigDecimal.ONE
            } else {
                priceOf(key) ?: return Resource.error(WalletError.AssetUnpriced)
            }
        val asset =
            runCatching { Asset(line.symbol, line.decimals) }.getOrNull()
                ?: return Resource.error(WalletError.AssetUnpriced)
        return Resource.success(PaymentCoin(asset, line.tokenAddress.trim(), price, line.amountMinor))
    }

    /**
     * The feed's price at the 18 decimals it is kept with (so the price stored is the price used), only when it is a
     * real price (above zero at that scale).
     */
    private suspend fun priceOf(key: String): BigDecimal? =
        prices
            .usdPrice(key)
            ?.setScale(PRICE_SCALE, RoundingMode.HALF_UP)
            ?.takeIf { it.signum() > 0 }
            ?.stripTrailingZeros()

    private companion object {
        /** `usd_price numeric(38,18)`. */
        const val PRICE_SCALE = 18
    }
}
