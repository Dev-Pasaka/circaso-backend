package com.circaso.ramp.data.support

import com.circaso.integrations.api.KycDocument
import com.circaso.ramp.domain.ports.IdentityKeys
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The KYC identity key (decision 2026-11-22):
 *
 * `HMAC-SHA256(identity key secret, COUNTRY | TYPE | NUMBER)`, as lower-case hex.
 *
 * - **The secret** is derived from the ramp's master key, never a secret of its own:
 *   `HMAC-SHA256(RAMP_CREDENTIALS_MASTER_KEY, "ramp:identity-key:v1")`. The label keeps it apart from the credential
 *   envelope's key, which is derived from the same master key under another label.
 * - **The message** is normalised so the same document always gives the same key: each part trimmed and upper-cased,
 *   inner whitespace collapsed in the country and the type, and everything but letters and digits removed from the
 *   number (`a 123-456/7` and `A1234567` are one document).
 *
 * **The master key must never change.** Every identity key ever stored was made with it: under another key the same
 * document gives another key, so "one identity, one account" and every block by identity silently stop matching.
 *
 * [masterKey] is asked on each use; null means the server has none, and then no key can be computed. Nothing here
 * logs or keeps a document.
 */
class HmacIdentityKeys(private val masterKey: () -> ByteArray?) : IdentityKeys {
    override val isAvailable: Boolean get() = masterKey() != null

    override fun of(document: KycDocument): String? {
        val master = masterKey() ?: return null
        val secret = hmac(master, LABEL.toByteArray())
        return hmac(secret, message(document).toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun hmac(key: ByteArray, message: ByteArray): ByteArray =
        Mac
            .getInstance(HMAC)
            .apply { init(SecretKeySpec(key, HMAC)) }
            .doFinal(message)

    internal companion object {
        private const val HMAC = "HmacSHA256"
        private const val LABEL = "ramp:identity-key:v1"
        private const val SEPARATOR = "|"
        private val WHITESPACE = Regex("""\s+""")
        private val NOT_LETTER_OR_DIGIT = Regex("""[^\p{L}\p{N}]""")

        /** What is signed for [document]: `COUNTRY|TYPE|NUMBER`, normalised. */
        internal fun message(document: KycDocument): String =
            listOf(
                words(document.issuingCountry),
                words(document.type),
                document.number.uppercase().replace(NOT_LETTER_OR_DIGIT, ""),
            ).joinToString(SEPARATOR)

        private fun words(text: String): String = text.trim().uppercase().replace(WHITESPACE, " ")
    }
}
