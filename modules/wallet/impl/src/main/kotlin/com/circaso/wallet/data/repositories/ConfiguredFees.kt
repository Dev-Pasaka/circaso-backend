package com.circaso.wallet.data.repositories

import com.circaso.wallet.domain.ports.FeeConfig
import java.math.BigDecimal

/**
 * The fee settings as the old server configuration had them (kept as the import-once seed of `wallet.chain_fees`,
 * decision 2026-11-18; [StoredFees] is what the wallet reads), per chain ([setting] reads `wallet.fees.chain.<chain>.<name>`, env
 * `WALLET_FEES_CHAIN_BASE_SEPOLIA_ADDRESS`): `address`, Circaso's fee wallet there (a Circle developer-controlled
 * wallet created once in Circle's console; a chain without one takes no fee), and `native-usd`, a dollar price per
 * native coin.
 */
class ConfiguredFees(private val setting: (blockchain: String, name: String) -> String?) : FeeConfig {
    override fun feeAddress(blockchain: String): String? =
        setting(blockchain, "address")?.trim()?.takeIf { it.isNotEmpty() }

    override fun nativeUsd(blockchain: String): BigDecimal = configuredNativeUsd(blockchain) ?: defaultPrice(blockchain)

    override fun configuredNativeUsd(blockchain: String): BigDecimal? =
        setting(blockchain, "native-usd")?.trim()?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }

    companion object {
        /**
         * Deliberately high prices, so the gas floor errs towards covering the cost (the fee covers gas, never the
         * other way round). TODO(decision: crypto price source) replace with a live feed.
         */
        fun defaultPrice(blockchain: String): BigDecimal =
            when {
                blockchain.startsWith("MATIC") -> BigDecimal("1")
                blockchain.startsWith("SOL") -> BigDecimal("300")
                blockchain.startsWith("AVAX") -> BigDecimal("60")
                else -> BigDecimal("5000")
            }
    }
}
