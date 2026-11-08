package com.circaso.integrations.impl.apns

import com.circaso.shared.CircasoJson
import kotlinx.serialization.Serializable
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.util.Base64

/** The provider token's header: ES256, signed with the `.p8` key [kid]. */
@Serializable
private data class ApnsTokenHeader(val alg: String = "ES256", val kid: String)

/** Its claims: our Apple team [iss] and when it was made [iat]. APNs takes no `exp`; a token lives an hour. */
@Serializable
private data class ApnsTokenClaims(val iss: String, val iat: Long)

/**
 * The APNs provider token (token-based auth): an ES256 JWT signed with the team's `.p8` key. Apple refuses a token
 * older than an hour and also one refreshed more than once every 20 minutes, so it is made once and reused for
 * [REFRESH_AFTER_SECONDS] (50 minutes); [expired] makes the next one early only when the cached one is at least
 * [MIN_AGE_SECONDS] (20 minutes) old. The key and the token are never logged.
 *
 * The signature is the raw `R || S` (64 bytes) JWS asks for: the JDK's `SHA256withECDSAinP1363Format` gives it
 * directly, as Apple sign-in's client secret already does, so there is no DER signature to unpack.
 */
class ApnsProviderToken(
    private val keyId: String,
    private val teamId: String,
    private val key: PrivateKey,
    private val clock: Clock = Clock.systemUTC(),
) {
    private var token: String? = null
    private var issuedAt = 0L

    /** The token to send now: the cached one, or a new one when it is 50 minutes old. */
    @Synchronized
    fun current(): String {
        val now = clock.instant().epochSecond
        val cached = token
        if (cached != null && now - issuedAt < REFRESH_AFTER_SECONDS) return cached
        return sign(now).also {
            token = it
            issuedAt = now
        }
    }

    /** APNs answered `ExpiredProviderToken` or `InvalidProviderToken`: drop the token if Apple allows a new one yet. */
    @Synchronized
    fun expired() {
        if (clock.instant().epochSecond - issuedAt >= MIN_AGE_SECONDS) token = null
    }

    private fun sign(iat: Long): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = CircasoJson.encodeToString(ApnsTokenHeader.serializer(), ApnsTokenHeader(kid = keyId))
        val claims = CircasoJson.encodeToString(ApnsTokenClaims.serializer(), ApnsTokenClaims(teamId, iat))
        val input = enc.encodeToString(header.toByteArray()) + "." + enc.encodeToString(claims.toByteArray())
        val signature =
            Signature.getInstance("SHA256withECDSAinP1363Format").run {
                initSign(key)
                update(input.toByteArray(Charsets.US_ASCII))
                sign()
            }
        return input + "." + enc.encodeToString(signature)
    }

    override fun toString(): String = "ApnsProviderToken(kid=$keyId, ***)"

    companion object {
        const val REFRESH_AFTER_SECONDS = 50 * 60L
        const val MIN_AGE_SECONDS = 20 * 60L

        /**
         * The `.p8` key as configured: base64 of the file (what `INTEGRATIONS_APNS_KEY` holds), or the PEM text itself,
         * with real or `\n`-escaped line breaks.
         */
        fun parseKey(configured: String): PrivateKey {
            val trimmed = configured.trim()
            val pem =
                if (trimmed.contains("BEGIN PRIVATE KEY")) {
                    trimmed
                } else {
                    String(Base64.getMimeDecoder().decode(trimmed), Charsets.UTF_8)
                }
            require(pem.contains("BEGIN PRIVATE KEY")) { "the APNs key is not a .p8 (PKCS#8) private key" }
            val der =
                Base64.getMimeDecoder().decode(
                    pem
                        .replace("-----BEGIN PRIVATE KEY-----", "")
                        .replace("-----END PRIVATE KEY-----", "")
                        .replace("\\n", "")
                        .replace("\\s".toRegex(), ""),
                )
            return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der))
        }
    }
}
