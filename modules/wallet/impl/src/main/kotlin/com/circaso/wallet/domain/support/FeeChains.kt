package com.circaso.wallet.domain.support

import com.circaso.shared.Network
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/** What the fee wallets need to know about chains, and the fixed names and keys they are created with. */
object FeeChains {
    /** Circle's name for the wallet set, as the script that made the testnet's used it. */
    const val WALLET_SET_NAME = "Circaso fees"
    const val WALLET_NAME = "Circaso fee wallet"

    /** The `refId` every fee wallet carries at Circle: how one made earlier is recognised. */
    const val REF_ID = "circaso-fees"
    const val NATIVE_DECIMALS = 18

    private val NOT_EVM = listOf("SOL", "NEAR", "APTOS")

    /**
     * Whether a fee can be taken on [blockchain]: EVM chains only (decision 2026-11-18: no Circaso fee on Solana, and
     * a fee wallet is one EOA address derived across EVM chains).
     */
    fun canCarryFee(blockchain: String): Boolean = NOT_EVM.none { blockchain.uppercase().startsWith(it) }

    /** The chain's own coin, which pays the network fee: POL on Polygon, AVAX on Avalanche, ETH on the rest. */
    fun nativeSymbol(blockchain: String): String {
        val name = blockchain.uppercase()
        return when {
            name.startsWith("MATIC") -> "POL"
            name.startsWith("AVAX") -> "AVAX"
            name.startsWith("MONAD") -> "MON"
            else -> "ETH"
        }
    }

    /**
     * What a withdrawal's network fee is taken to be when Circle won't estimate it, in the chain's own coin. Set well
     * above what a token transfer costs on a busy day, so a withdrawal that passes this never fails for gas: Ethereum
     * 0.005 ETH (65 000 gas at about 75 gwei), Polygon 0.05 POL, Avalanche 0.005 AVAX, every rollup (Base, Arbitrum,
     * Optimism, Unichain and the rest) 0.0002 ETH.
     */
    fun gasFloor(blockchain: String): BigDecimal {
        val name = blockchain.uppercase()
        return when {
            name == "ETH" || name.startsWith("ETH-") -> BigDecimal("0.005")
            name.startsWith("MATIC") -> BigDecimal("0.05")
            name.startsWith("AVAX") -> BigDecimal("0.005")
            else -> BigDecimal("0.0002")
        }
    }

    /**
     * The idempotency key of one creation step on [network]: always the same for the same step, so two servers starting
     * together, or one restarting half way, ask Circle for the same thing and get the same answer. Shaped as a random
     * (version 4) UUID, which is what Circle takes.
     */
    fun creationKey(network: Network, step: String): UUID {
        val hash =
            MessageDigest
                .getInstance("SHA-256")
                .digest("circaso-fees:${network.name}:$step".toByteArray(StandardCharsets.UTF_8))
        hash[6] = ((hash[6].toInt() and 0x0f) or 0x40).toByte()
        hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()
        var most = 0L
        var least = 0L
        for (i in 0 until 8) most = (most shl 8) or (hash[i].toLong() and 0xff)
        for (i in 8 until 16) least = (least shl 8) or (hash[i].toLong() and 0xff)
        return UUID(most, least)
    }
}
