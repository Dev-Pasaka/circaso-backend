package com.circaso.integrations.impl.circle

/**
 * Circle's configuration, which no business module sees: [appId] for the phone's SDK, the [blockchain] and
 * [accountType] new wallets are created with, and USDC's contract address on that chain. USDC is named by its
 * address, published by Circle (developers.circle.com/stablecoins/usdc-contract-addresses), rather than by Circle's
 * internal token id: transfers accept `tokenAddress` + `blockchain`, and the address can be checked on any explorer.
 */
data class CircleSettings(
    val appId: String,
    val blockchain: String = "BASE-SEPOLIA",
    val accountType: String = "SCA",
    val usdcTokenAddress: String,
) {
    /** Addresses compare in lowercase: Circle answers in either case. */
    fun isUsdc(tokenAddress: String, onChain: String): Boolean =
        onChain == blockchain && tokenAddress.equals(usdcTokenAddress, ignoreCase = true)
}
