package com.circaso.integrations.impl.circle

import java.math.BigInteger

/** The one ERC-20 call a split payment makes: `transfer(address,uint256)`, ABI-encoded as Circle's batch takes it. */
internal object Erc20 {
    /** `keccak256("transfer(address,uint256)")`'s first four bytes. */
    private const val TRANSFER = "a9059cbb"

    private val ADDRESS = Regex("0x[0-9a-fA-F]{40}")

    /** `0xa9059cbb` + the address and the amount, each left-padded to 32 bytes; null for a non-EVM address. */
    fun transfer(to: String, amountMinor: BigInteger): String? {
        if (!isAddress(to) || amountMinor.signum() < 0 || amountMinor.bitLength() > UINT256_BITS) return null
        val address = to.removePrefix("0x").lowercase().padStart(WORD, '0')
        val amount = amountMinor.toString(HEX).padStart(WORD, '0')
        return "0x$TRANSFER$address$amount"
    }

    fun transfer(to: String, amountMinor: Long): String? = transfer(to, BigInteger.valueOf(amountMinor))

    /** An EVM address: `0x` and 40 hex digits. */
    fun isAddress(text: String): Boolean = ADDRESS.matches(text)

    private const val UINT256_BITS = 256

    /** One ABI word in hex digits. */
    private const val WORD = 64
    private const val HEX = 16
}
