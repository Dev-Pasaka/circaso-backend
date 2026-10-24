package com.circaso.shared

import kotlinx.serialization.Serializable

/**
 * What an amount is counted in. [decimals] is how many minor-unit digits one whole unit has.
 * Only the stablecoins the wallet will hold are listed; a new asset is added here and nowhere else.
 */
enum class Asset(val decimals: Int) {
    USDC(6),
    USDT(6),
}

/** Integer minor units plus the asset, never floating point. `Money(1_500_000, Asset.USDC)` is 1.5 USDC. */
@Serializable
data class Money(val amountMinor: Long, val asset: Asset) : Comparable<Money> {
    val isZero: Boolean get() = amountMinor == 0L
    val isNegative: Boolean get() = amountMinor < 0L

    operator fun plus(other: Money): Money =
        Money(
            Math.addExact(amountMinor, other.requireSameAsset().amountMinor),
            asset,
        )

    operator fun minus(other: Money): Money =
        Money(
            Math.subtractExact(amountMinor, other.requireSameAsset().amountMinor),
            asset,
        )

    override fun compareTo(other: Money): Int = amountMinor.compareTo(other.requireSameAsset().amountMinor)

    private fun Money.requireSameAsset(): Money {
        require(asset == this@Money.asset) { "Cannot combine ${this@Money.asset} with $asset" }
        return this
    }

    /** Amounts never reach a log line, so the string form hides the value. */
    override fun toString(): String = "Money(***, $asset)"

    companion object {
        fun zero(asset: Asset): Money = Money(0, asset)
    }
}
