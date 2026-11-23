package com.circaso.integrations.impl.payhero

import org.slf4j.Logger
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * FinoQ's (PayHero's) webhook signature, as far as it can be known (2026-11-23). FinoQ's dashboard gives a merchant a
 * webhook signing secret, but **neither docs.payhero.africa nor docs.payhero.co.ke documents a signature**: no header,
 * algorithm, encoding or timestamp. So this is built to learn the scheme without dropping a real callback:
 *
 * - **Every layout of header names is logged once** ([noteHeaders]), names only, never values, so the first real
 *   callback shows which header carries the signature.
 * - **A header is checked only if its name is one a provider commonly signs with** ([HEADERS]). Its value is accepted
 *   when it is the HMAC-SHA256 of the raw body under the secret (hex, base64 or base64url; an optional `sha256=`
 *   prefix) or the secret itself (a provider that sends a shared token). Each comparison is constant-time.
 * - **No recognised header is [Check.Unsigned]**, which the caller accepts (logged), so a scheme we guessed wrong
 *   never drops a real payout's doorbell. Only a recognised header whose value matches none of the forms is
 *   [Check.Mismatch].
 *
 * Nothing here logs a header's value, the secret or the body.
 */
internal class CallbackSignatures(private val log: Logger) {
    private val seenLayouts = ConcurrentHashMap.newKeySet<String>()

    /** One INFO line the first time each set of header names is seen: the names, sorted, and nothing else. */
    fun noteHeaders(headers: Map<String, String>) {
        val names =
            headers.keys
                .map(::cleanName)
                .distinct()
                .sorted()
                .take(MAX_NAMES)
                .joinToString(",")
        if (seenLayouts.size >= MAX_LAYOUTS || !seenLayouts.add(names)) return
        val signing = headers.keys.map(::cleanName).filter { it in HEADERS }
        log.info("payhero callback headers first seen: names=[{}] signing={}", names, signing.ifEmpty { "none" })
    }

    /** What the recognised signature headers in [headers] say about [body] under [secret]. */
    fun check(
        secret: String,
        headers: Map<String, String>,
        body: ByteArray,
    ): Check {
        val present = HEADERS.mapNotNull { name -> headers[name]?.let { name to it } }
        if (present.isEmpty()) return Check.Unsigned
        val expected = Expected.of(secret, body)
        val matched = present.firstNotNullOfOrNull { (name, value) -> expected.match(value)?.let { name to it } }
        return matched?.let { (name, form) -> Check.Verified(name, form) } ?: Check.Mismatch(present.first().first)
    }

    sealed interface Check {
        /** No header this knows carries a signature. */
        data object Unsigned : Check

        /** [header]'s value is the body signed with the secret, in [form]. */
        data class Verified(val header: String, val form: String) : Check

        /** A signature header is there and doesn't match: a forgery, or a scheme this doesn't know. */
        data class Mismatch(val header: String) : Check
    }

    /** The forms a correct signature may take; a presented value is compared with each, in constant time. */
    private class Expected(
        private val hex: ByteArray,
        private val base64: ByteArray,
        private val base64Url: ByteArray,
        private val token: ByteArray,
    ) {
        fun match(presented: String): String? {
            val value = presented.trim().removePrefix(SHA256_PREFIX)
            val forms =
                listOf(
                    FORM_HEX to equal(value.lowercase(), hex),
                    FORM_BASE64 to equal(value, base64),
                    FORM_BASE64URL to equal(value.trimEnd('='), base64Url),
                    FORM_TOKEN to equal(presented.trim(), token),
                )
            return forms.firstOrNull { it.second }?.first
        }

        private fun equal(presented: String, expected: ByteArray): Boolean =
            MessageDigest.isEqual(presented.toByteArray(), expected)

        companion object {
            fun of(secret: String, body: ByteArray): Expected {
                val mac =
                    Mac
                        .getInstance(HMAC)
                        .apply { init(SecretKeySpec(secret.toByteArray(), HMAC)) }
                        .doFinal(body)
                return Expected(
                    hex = mac.joinToString("") { "%02x".format(it) }.toByteArray(),
                    base64 = Base64.getEncoder().encode(mac),
                    base64Url = Base64.getUrlEncoder().withoutPadding().encode(mac),
                    token = secret.toByteArray(),
                )
            }
        }
    }

    companion object {
        /**
         * Header names providers commonly sign with. None is confirmed for FinoQ: the header-name line of the first
         * real callback says which (if any) it sends, and this list is then cut down to it.
         */
        val HEADERS: List<String> =
            listOf(
                "x-finoq-signature",
                "x-payhero-signature",
                "x-webhook-signature",
                "x-callback-signature",
                "x-signature",
                "x-hub-signature-256",
                "signature",
                "x-finoq-webhook-secret",
                "x-payhero-webhook-secret",
                "x-webhook-secret",
            )

        private const val HMAC = "HmacSHA256"
        private const val SHA256_PREFIX = "sha256="
        private const val FORM_HEX = "hmac-sha256-hex"
        private const val FORM_BASE64 = "hmac-sha256-base64"
        private const val FORM_BASE64URL = "hmac-sha256-base64url"
        private const val FORM_TOKEN = "shared-token"
        private const val MAX_NAMES = 60
        private const val MAX_NAME_LENGTH = 64
        private const val MAX_LAYOUTS = 200
        private val NOT_NAME = Regex("[^a-z0-9_-]")

        /** A header name as it may be logged: lowercase letters, digits, `-` and `_` only, and not too long. */
        private fun cleanName(name: String): String =
            name
                .lowercase()
                .replace(NOT_NAME, "?")
                .take(MAX_NAME_LENGTH)
    }
}
