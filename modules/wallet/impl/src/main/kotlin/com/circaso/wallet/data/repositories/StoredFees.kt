package com.circaso.wallet.data.repositories

import com.circaso.wallet.domain.ports.FeeConfig
import com.circaso.wallet.domain.support.Networks
import java.math.BigDecimal

/**
 * The fee settings from `wallet.chain_fees` (decision 2026-11-18): Circaso's fee wallet per chain and the fallback
 * price of the chain's own coin, as [Networks] last read them. The table is seeded once from the old
 * `WALLET_FEES_CHAIN_<CHAIN>_*` variables ([seed]) and then wins: an address written there (by the fee-wallet setup,
 * or by hand) is in force within seconds, on both servers. Until the table has been read in this process the seed
 * answers, which is what the first read writes.
 */
class StoredFees(private val networks: Networks, private val seed: FeeConfig) : FeeConfig {
    override fun feeAddress(blockchain: String): String? {
        if (!networks.isLoaded) return seed.feeAddress(blockchain)
        return networks
            .feeOf(blockchain)
            ?.feeAddress
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    override fun nativeUsd(blockchain: String): BigDecimal =
        configuredNativeUsd(blockchain) ?: ConfiguredFees.defaultPrice(blockchain)

    override fun configuredNativeUsd(blockchain: String): BigDecimal? =
        if (networks.isLoaded) networks.feeOf(blockchain)?.nativeUsd else seed.configuredNativeUsd(blockchain)
}
