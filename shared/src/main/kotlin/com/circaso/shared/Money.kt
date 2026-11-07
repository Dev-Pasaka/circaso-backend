package com.circaso.shared

import java.math.BigDecimal
import java.math.BigInteger

/**
 * What an amount is counted in: a coin by its [symbol] (`USDC`, `EURC`, `ETH`) and [decimals], how many minor-unit
 * digits one whole coin has. Any coin a wallet holds can be one (decision 2026-11-07: a person pays any coin they
 * hold); the symbol alone never says which contract it is, so whoever moves money pairs it with the coin's contract on
 * its chain. [USDC] and [USDT] are the US dollar stablecoins Circaso offers.
 */
data class Asset(val symbol: String, val decimals: Int) {
    init {
        require(symbol.isNotBlank() && symbol == symbol.trim().uppercase()) { "An asset's symbol is upper case" }
        require(decimals in 0..MAX_DECIMALS) { "An asset has 0 to $MAX_DECIMALS decimals" }
    }

    /** One of the US dollar stablecoins Circaso offers ([DOLLAR_STABLECOINS]), worth one dollar each. */
    val isDollarStablecoin: Boolean get() = this in DOLLAR_STABLECOINS

    override fun toString(): String = symbol

    companion object {
        /** The most decimals a coin may have (Circle's limit for a token it describes). */
        const val MAX_DECIMALS = 36

        val USDC = Asset("USDC", 6)
        val USDT = Asset("USDT", 6)

        /** The US dollar stablecoins Circaso offers, one dollar each. */
        val DOLLAR_STABLECOINS: List<Asset> = listOf(USDC, USDT)

        /** One of [DOLLAR_STABLECOINS] by its symbol (any case), or null. */
        fun dollarStablecoin(symbol: String): Asset? {
            val wanted = symbol.trim().uppercase()
            return DOLLAR_STABLECOINS.firstOrNull { it.symbol == wanted }
        }
    }
}

/**
 * Integer minor units plus the asset, never floating point. `Money(1_500_000, Asset.USDC)` is 1.5 USDC. A big integer:
 * an 18-decimal coin (ETH, POL) passes a `Long` at about 9 whole coins.
 */
data class Money(val amountMinor: BigInteger, val asset: Asset) : Comparable<Money> {
    constructor(amountMinor: Long, asset: Asset) : this(BigInteger.valueOf(amountMinor), asset)

    val isZero: Boolean get() = amountMinor.signum() == 0
    val isNegative: Boolean get() = amountMinor.signum() < 0

    /** The amount in whole coins, exactly ("20.5" USDC for 20 500 000 minor units). */
    val whole: BigDecimal get() = BigDecimal(amountMinor, asset.decimals)

    operator fun plus(other: Money): Money = Money(amountMinor + other.requireSameAsset().amountMinor, asset)

    operator fun minus(other: Money): Money = Money(amountMinor - other.requireSameAsset().amountMinor, asset)

    override fun compareTo(other: Money): Int = amountMinor.compareTo(other.requireSameAsset().amountMinor)

    private fun Money.requireSameAsset(): Money {
        require(asset == this@Money.asset) { "Cannot combine ${this@Money.asset} with $asset" }
        return this
    }

    /** Amounts never reach a log line, so the string form hides the value. */
    override fun toString(): String = "Money(***, $asset)"

    companion object {
        fun zero(asset: Asset): Money = Money(BigInteger.ZERO, asset)
    }
}
