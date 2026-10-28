package com.circaso.integrations.impl

import com.circaso.infrastructure.api.Config
import com.circaso.integrations.api.CustodyAsset
import com.circaso.integrations.api.CustodyChain
import com.circaso.integrations.api.SupportedChains

/**
 * The chains a wallet can be on and the assets offered on each (decision 2026-10-28: Base, Polygon, Ethereum and
 * Solana at launch, USDC on each), read from the `INTEGRATIONS_CUSTODY_*` settings, the same for every custody
 * provider so `log` and `fake` offer what `circle` would:
 *
 * - `INTEGRATIONS_CUSTODY_CHAINS`: the supported chains by Circle's names, comma separated (default: the four
 *   testnets, `BASE-SEPOLIA,MATIC-AMOY,ETH-SEPOLIA,SOL-DEVNET`).
 * - `INTEGRATIONS_CUSTODY_DEFAULT_CHAIN`: where a new wallet starts (default `BASE-SEPOLIA`; `BASE` in production).
 * - `INTEGRATIONS_CUSTODY_CHAIN_<CHAIN>_ASSETS`: the assets on that chain, comma separated, each
 *   `SYMBOL:tokenAddress:decimals`, with `:usd` after a stablecoin worth one US dollar (empty tokenAddress for the
 *   native coin). Default for the four testnets: their USDC. A chain with no assets stops startup.
 * - `INTEGRATIONS_CUSTODY_CHAIN_<CHAIN>_ACCOUNT_TYPE`: `SCA` (default; Gas Station pays the gas) or `EOA` (default on
 *   Solana, which has no SCA).
 *
 * `<CHAIN>` is the chain's name with `-` as `_` (`BASE_SEPOLIA`).
 */
internal object CustodyChainsConfig {
    /** What the custody contract offers, and the account type per chain, which only Circle uses. */
    data class Parsed(val supported: SupportedChains, val accountTypes: Map<String, String>)

    /** USDC on each testnet, from developers.circle.com/stablecoins/usdc-contract-addresses. */
    private val TESTNET_USDC =
        linkedMapOf(
            "BASE-SEPOLIA" to "0x036CbD53842c5426634e7929541eC2318f3dCF7e",
            "MATIC-AMOY" to "0x41E94Eb019C0762f9Bfcf9Fb1E58725BfB0e7582",
            "ETH-SEPOLIA" to "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238",
            "SOL-DEVNET" to "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU",
        )

    private const val DEFAULT_CHAIN = "BASE-SEPOLIA"
    private const val USDC_DECIMALS = 6

    val TESTNETS: SupportedChains =
        SupportedChains(
            default = DEFAULT_CHAIN,
            chains = TESTNET_USDC.map { (chain, address) -> CustodyChain(chain, listOf(usdc(address))) },
        )

    fun parse(custody: Config): Parsed {
        val names =
            custody
                .string("chains", TESTNET_USDC.keys.joinToString(","))
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        check(names.isNotEmpty()) { "integrations.custody.chains must name at least one chain" }
        val default = custody.string("default-chain", DEFAULT_CHAIN)
        check(default in names) { "integrations.custody.default-chain ($default) must be one of the chains" }

        val chains = names.map { CustodyChain(it, assetsOf(custody, it)) }
        val accountTypes = names.associateWith { custody.string("${key(it)}.account-type", defaultAccountType(it)) }
        return Parsed(SupportedChains(default, chains), accountTypes)
    }

    /** Solana has no smart-contract accounts; every EVM chain uses them so Gas Station can pay. */
    fun defaultAccountType(blockchain: String): String = if (blockchain.startsWith("SOL")) "EOA" else "SCA"

    /**
     * Whether two token addresses are the same token: EVM hex addresses compare in any case (Circle answers in
     * either); anything else, a Solana mint for one, is case sensitive.
     */
    fun sameToken(configured: String, reported: String): Boolean =
        if (configured.startsWith("0x")) configured.equals(reported, ignoreCase = true) else configured == reported

    private fun assetsOf(custody: Config, chain: String): List<CustodyAsset> {
        val raw = custody.string("${key(chain)}.assets") ?: TESTNET_USDC[chain]?.let { "USDC:$it:$USDC_DECIMALS:usd" }
        checkNotNull(raw) { "Set INTEGRATIONS_CUSTODY_CHAIN_${chain.replace('-', '_')}_ASSETS for $chain" }
        val assets =
            raw
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { asset(chain, it) }
        check(assets.isNotEmpty()) { "$chain must offer at least one asset" }
        return assets
    }

    private fun asset(chain: String, spec: String): CustodyAsset {
        val parts = spec.split(':')
        val decimals = parts.getOrNull(2)?.toIntOrNull()
        check(parts.size in 3..4 && parts[0].isNotBlank() && decimals != null && decimals in 0..36) {
            "An asset on $chain must be SYMBOL:tokenAddress:decimals[:usd] (was '$spec')"
        }
        val peg = parts.getOrNull(3)
        check(peg == null || peg.equals("usd", ignoreCase = true)) { "Only :usd may follow an asset on $chain" }
        return CustodyAsset(parts[0].uppercase(), parts[1], decimals, usdPegged = peg != null)
    }

    private fun usdc(address: String) = CustodyAsset("USDC", address, USDC_DECIMALS, usdPegged = true)

    private fun key(chain: String) = "chain.${chain.lowercase()}"
}
