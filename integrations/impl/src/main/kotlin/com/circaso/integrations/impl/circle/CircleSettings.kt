package com.circaso.integrations.impl.circle

import com.circaso.integrations.api.CustodyAsset
import com.circaso.integrations.api.SupportedChains
import com.circaso.integrations.impl.CustodyChainsConfig

/**
 * Circle's configuration, which no business module sees: [appId] for the phone's SDK, the [supported] chains with
 * the assets offered on each, and the account type wallets are created with per chain ([accountTypes]; SCA, which
 * Gas Station needs on EVM chains, unless a chain says otherwise). Tokens are named by their contract address,
 * published by the issuer (developers.circle.com/stablecoins/usdc-contract-addresses), rather than by Circle's
 * internal token id: transfers accept `tokenAddress` + `blockchain`, and the address can be checked on any explorer.
 */
data class CircleSettings(
    val appId: String,
    val supported: SupportedChains = CustodyChainsConfig.TESTNETS,
    val accountTypes: Map<String, String> = emptyMap(),
) {
    fun accountType(blockchain: String): String =
        accountTypes[blockchain] ?: CustodyChainsConfig.defaultAccountType(blockchain)

    /** USDC as offered on [blockchain], or null when the chain isn't supported or doesn't offer it. */
    fun usdcOn(blockchain: String): CustodyAsset? =
        supported.find(blockchain)?.assets?.firstOrNull { it.symbol == USDC }

    /** Whether Circle's token ([tokenAddress] on [onChain]) is the USDC we offer there. */
    fun isUsdc(tokenAddress: String, onChain: String): Boolean =
        usdcOn(onChain)?.let { CustodyChainsConfig.sameToken(it.tokenAddress, tokenAddress) } == true

    /** The offered asset Circle's token is, or null for a token we don't offer. */
    fun offered(tokenAddress: String, onChain: String): CustodyAsset? =
        supported.find(onChain)?.assets?.firstOrNull { CustodyChainsConfig.sameToken(it.tokenAddress, tokenAddress) }

    /**
     * The contract to send [symbol] by on [blockchain]: [tokenAddress] as the wallet gave it (empty for the chain's own
     * coin), or, when it gave none, the asset offered there under [symbol]; null when neither names a coin.
     */
    fun coinAddress(
        blockchain: String,
        symbol: String,
        tokenAddress: String?,
    ): String? =
        tokenAddress?.trim()
            ?: supported
                .find(blockchain)
                ?.assets
                ?.firstOrNull { it.symbol == symbol }
                ?.tokenAddress

    private companion object {
        const val USDC = "USDC"
    }
}
