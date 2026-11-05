package com.circaso.integrations.impl.circle

import java.math.BigInteger

/** The one ERC-20 call a split payment makes: `transfer(address,uint256)`, ABI-encoded as Circle's batch takes it. */
internal object Erc20 {
    /** `keccak256("transfer(address,uint256)")`'s first four bytes. */
    private const val TRANSFER = "a9059cbb"

    private val ADDRESS = Regex("0x[0-9a-fA-F]{40}")

    /** `0xa9059cbb` + the address and the amount, each left-padded to 32 bytes; null for a non-EVM address. */
    fun transfer(to: String, amountMinor: Long): String? {
        if (!ADDRESS.matches(to) || amountMinor < 0) return null
        val address = to.removePrefix("0x").lowercase().padStart(WORD, '0')
        val amount = BigInteger.valueOf(amountMinor).toString(HEX).padStart(WORD, '0')
        return "0x$TRANSFER$address$amount"
    }

    /** One ABI word in hex digits. */
    private const val WORD = 64
    private const val HEX = 16
}
