package com.circaso.wallet.domain.ports

import java.math.BigDecimal

/**
 * The fee settings that are server configuration, never a request's (spaces-plan §7b): Circaso's fee wallet on each
 * chain (a Circle developer-controlled wallet in Circaso's own wallet set, used for nothing else), and what one native
 * coin of a chain is worth in dollars, to price the gas Circaso sponsors.
 */
interface FeeConfig {
    /** Circaso's fee wallet address on [blockchain], or null when none is configured there (then no fee is taken). */
    fun feeAddress(blockchain: String): String?

    /**
     * Dollars per native coin of [blockchain] (ETH, POL, SOL).
     * TODO(decision: crypto price source) configured prices until a live price feed is chosen.
     */
    fun nativeUsd(blockchain: String): BigDecimal
}
