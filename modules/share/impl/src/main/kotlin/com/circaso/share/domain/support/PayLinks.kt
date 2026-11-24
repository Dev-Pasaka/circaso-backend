package com.circaso.share.domain.support

import com.circaso.share.domain.models.PayLink

/**
 * Pay links (decision 2026-11-24): `https://<share host>/pay/<address>?chain=<BASE|…>&asset=USDC[&amount=<decimal>]`,
 * the one form Circaso's payment QR codes take. The recipient is the only address in the link, so a scanner that pulls
 * the first `0x…` out of a code gets the right one (an EIP-681 code's first is the token contract). The Circaso app
 * reads the link and fills in the recipient, the network and the coin; anything else opens this page.
 *
 * Strict: a link whose address isn't valid for its chain (EIP-55 when mixed case, a 32-byte base58 key on Solana), whose
 * chain Circaso doesn't offer or whose coin isn't USDC is never shown as payable.
 */
object PayLinks {
    /** Circle's names for the chains Circaso offers USDC on. */
    val CHAINS = setOf("BASE", "BASE-SEPOLIA", "MATIC", "MATIC-AMOY", "ETH", "ETH-SEPOLIA", "SOL", "SOL-DEVNET")
    private const val ASSET = "USDC"
    private const val EVM_HEX = 40
    private const val SOLANA_KEY_BYTES = 32
    private const val UPPER_FROM = 8
    private val EVM = Regex("^0x[0-9a-fA-F]{40}$")
    private val BASE58 = Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$")
    private val AMOUNT = Regex("^[0-9]{1,12}(\\.[0-9]{1,6})?$")
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    fun read(
        address: String?,
        chain: String?,
        asset: String?,
        amount: String?,
    ): PayLink {
        val to = address?.trim().orEmpty()
        val solanaLike = BASE58.matches(to)
        if (!EVM.matches(to) && !solanaLike) return PayLink.BadAddress
        val blockchain = chain?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        if (blockchain == null || blockchain !in CHAINS) return PayLink.UnknownChain(blockchain?.take(MAX_CHAIN))
        if (asset != null && !asset.trim().equals(ASSET, ignoreCase = true)) return PayLink.NotUsdc
        val solana = blockchain.startsWith("SOL")
        val valid = if (solana) solanaLike && isSolanaKey(to) else EVM.matches(to) && checksumHolds(to)
        if (!valid) return PayLink.BadAddress
        val raw = amount?.trim()?.takeIf { it.isNotEmpty() }
        val readable = raw?.takeIf { AMOUNT.matches(it) }?.let(::normalised)?.takeIf { it != "0" }
        return PayLink.Payable(to, blockchain, readable, amountUnreadable = raw != null && readable == null)
    }

    /** All lower or all upper case has no checksum; mixed case must match EIP-55's. The zero address never pays. */
    fun checksumHolds(address: String): Boolean {
        val body = address.substring(2)
        if (body.all { it == '0' }) return false
        val mixed = body != body.lowercase() && body != body.uppercase()
        return !mixed || checksummed(body.lowercase()) == "0x$body"
    }

    /** The EIP-55 form of a lower-case address body (no `0x`). */
    fun checksummed(lower: String): String {
        require(lower.length == EVM_HEX)
        val hash = Keccak256.hash(lower.encodeToByteArray())
        val out = StringBuilder("0x")
        lower.forEachIndexed { i, c ->
            val byte = hash[i / 2].toInt() and 0xff
            val nibble = if (i % 2 == 0) byte shr 4 else byte and 0x0f
            out.append(if (c.isLetter() && nibble >= UPPER_FROM) c.uppercaseChar() else c)
        }
        return out.toString()
    }

    /** A Solana address is a base58 public key: it decodes to exactly 32 bytes. */
    fun isSolanaKey(text: String): Boolean {
        val bytes = mutableListOf<Int>()
        for (ch in text) {
            var carry = ALPHABET.indexOf(ch)
            if (carry < 0) return false
            for (i in bytes.indices) {
                carry += bytes[i] * 58
                bytes[i] = carry and 0xff
                carry = carry shr 8
            }
            while (carry > 0) {
                bytes += carry and 0xff
                carry = carry shr 8
            }
        }
        val leadingZeros = text.takeWhile { it == '1' }.length
        return bytes.size + leadingZeros == SOLANA_KEY_BYTES
    }

    /** "012.50" → "12.5"; at most six places, checked by the pattern. */
    private fun normalised(text: String): String {
        val whole = text.substringBefore('.').trimStart('0').ifEmpty { "0" }
        val frac = text.substringAfter('.', "").trimEnd('0')
        return if (frac.isEmpty()) whole else "$whole.$frac"
    }

    private const val MAX_CHAIN = 24
}
