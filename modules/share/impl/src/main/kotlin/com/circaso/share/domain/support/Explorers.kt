package com.circaso.share.domain.support

/** The public block explorer page for a transaction, as the app links it; null for a chain without one or no hash. */
object Explorers {
    fun transactionUrl(blockchain: String, txHash: String?): String? {
        val hash = txHash?.takeIf { it.isNotBlank() } ?: return null
        return when (blockchain.uppercase()) {
            "BASE" -> "https://basescan.org/tx/$hash"
            "BASE-SEPOLIA" -> "https://sepolia.basescan.org/tx/$hash"
            "ETH" -> "https://etherscan.io/tx/$hash"
            "ETH-SEPOLIA" -> "https://sepolia.etherscan.io/tx/$hash"
            "MATIC" -> "https://polygonscan.com/tx/$hash"
            "MATIC-AMOY" -> "https://amoy.polygonscan.com/tx/$hash"
            "ARB" -> "https://arbiscan.io/tx/$hash"
            "ARB-SEPOLIA" -> "https://sepolia.arbiscan.io/tx/$hash"
            "AVAX" -> "https://snowtrace.io/tx/$hash"
            "AVAX-FUJI" -> "https://testnet.snowtrace.io/tx/$hash"
            "SOL" -> "https://explorer.solana.com/tx/$hash"
            "SOL-DEVNET" -> "https://explorer.solana.com/tx/$hash?cluster=devnet"
            else -> null
        }
    }

    /** The explorer's own name for [transactionUrl]'s page ("Basescan"), or null for a chain without one. */
    fun explorerName(blockchain: String): String? {
        val up = blockchain.uppercase()
        return when {
            up.startsWith("BASE") -> "Basescan"
            up.startsWith("ETH") -> "Etherscan"
            up.startsWith("MATIC") -> "Polygonscan"
            up.startsWith("ARB") -> "Arbiscan"
            up.startsWith("AVAX") -> "Snowtrace"
            up.startsWith("SOL") -> "Solana Explorer"
            else -> null
        }
    }

    /** "Base", "Base (testnet)": the name people know a chain by. */
    fun chainName(blockchain: String): String {
        val up = blockchain.uppercase()
        val base =
            when {
                up.startsWith("BASE") -> "Base"
                up.startsWith("MATIC") || up.startsWith("POLYGON") -> "Polygon"
                up.startsWith("ETH") -> "Ethereum"
                up.startsWith("SOL") -> "Solana"
                up.startsWith("ARB") -> "Arbitrum"
                up.startsWith("AVAX") -> "Avalanche"
                up.startsWith("ARC") -> "Arc"
                else -> blockchain.lowercase().replaceFirstChar { it.uppercase() }
            }
        val isTestnet = TESTNET_SUFFIXES.any { up.endsWith(it) }
        return if (isTestnet) "$base (testnet)" else base
    }

    private val TESTNET_SUFFIXES = listOf("SEPOLIA", "AMOY", "DEVNET", "TESTNET", "FUJI")
}
