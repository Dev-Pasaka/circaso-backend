package com.circaso.ramp.data.support

import com.circaso.ramp.domain.ports.AddressCheck
import com.circaso.ramp.domain.ports.EvmAddresses
import org.bouncycastle.crypto.digests.KeccakDigest

/**
 * EVM addresses (EIP-55): `0x` and 40 hex digits. All lower or all upper case carries no checksum and is accepted; mixed
 * case must match the checksum, which catches a mistyped character. The zero address is refused: nothing sent there
 * can come back.
 */
class Eip55Addresses : EvmAddresses {
    override fun check(raw: String): AddressCheck {
        val trimmed = raw.trim()
        val body = trimmed.removePrefix("0x").removePrefix("0X")
        return when {
            body.length == trimmed.length -> {
                AddressCheck.Invalid("it must start with 0x")
            }

            body.length != HEX_DIGITS -> {
                AddressCheck.Invalid("it must be 0x and 40 hex characters")
            }

            !body.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } -> {
                AddressCheck.Invalid("it has characters that aren't hex")
            }

            body.all { it == '0' } -> {
                AddressCheck.Invalid("it is the zero address")
            }

            else -> {
                checked(body)
            }
        }
    }

    private fun checked(body: String): AddressCheck {
        val expected = checksummed(body.lowercase())
        val mixed = body != body.lowercase() && body != body.uppercase()
        return if (mixed && "0x$body" != expected) {
            AddressCheck.Invalid("its checksum doesn't match: check each character")
        } else {
            AddressCheck.Valid(expected)
        }
    }

    companion object {
        private const val HEX_DIGITS = 40
        private const val KECCAK_BITS = 256
        private const val UPPER_FROM = 8

        /** The EIP-55 form of a lower-case address body (no `0x`). */
        fun checksummed(lower: String): String {
            val digest = KeccakDigest(KECCAK_BITS)
            val bytes = lower.toByteArray(Charsets.US_ASCII)
            digest.update(bytes, 0, bytes.size)
            val hash = ByteArray(digest.digestSize).also { digest.doFinal(it, 0) }
            val out = StringBuilder("0x")
            lower.forEachIndexed { i, c ->
                val byte = hash[i / 2].toInt() and 0xff
                val nibble = if (i % 2 == 0) byte shr 4 else byte and 0x0f
                out.append(if (c.isLetter() && nibble >= UPPER_FROM) c.uppercaseChar() else c)
            }
            return out.toString()
        }
    }
}
