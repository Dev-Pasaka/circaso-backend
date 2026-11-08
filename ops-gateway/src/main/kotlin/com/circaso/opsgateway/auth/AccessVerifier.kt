package com.circaso.opsgateway.auth

import com.circaso.opsgateway.AccessSettings
import com.circaso.shared.CircasoJson
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.time.Clock
import java.time.Duration
import java.util.Base64

/** What checking a Cloudflare Access token found. */
sealed interface AccessCheck {
    data class Verified(val email: String) : AccessCheck

    /** Not a good token: say so, never which check failed (the log line has it, at DEBUG). */
    data class Invalid(val why: String) : AccessCheck

    /** The certificates couldn't be read: the token may be fine. */
    data object Unavailable : AccessCheck
}

/** One key from `https://<team>/cdn-cgi/access/certs`. */
@Serializable
data class AccessJwk(
    val kid: String,
    val kty: String,
    val n: String? = null,
    val e: String? = null,
    val alg: String? = null,
)

@Serializable
data class AccessJwks(val keys: List<AccessJwk> = emptyList())

/** Where the signing keys come from: Cloudflare in a real run, a local key pair in tests. */
fun interface AccessKeys {
    /** [refresh] asks again even if they were read lately: the token names a key we haven't seen (Access rotates). */
    suspend fun keys(refresh: Boolean): List<AccessJwk>?
}

@Serializable
private data class JwtHeader(val alg: String? = null, val kid: String? = null)

@Serializable
private data class ClaimsOneAudience(
    val aud: String? = null,
    val exp: Long? = null,
    val nbf: Long? = null,
    val iss: String? = null,
    val email: String? = null,
)

@Serializable
private data class ClaimsManyAudiences(
    val aud: List<String> = emptyList(),
    val exp: Long? = null,
    val nbf: Long? = null,
    val iss: String? = null,
    val email: String? = null,
)

private data class Claims(
    val audiences: List<String>,
    val exp: Long?,
    val nbf: Long?,
    val iss: String?,
    val email: String?,
)

/**
 * Verifies the Cloudflare Access application token (decision 2026-11-08; the same checks as coinx-web's `access.ts`):
 * RS256 only, a signature by one of the team's published keys, our application's audience tag, the team's issuer,
 * not expired, not before its time (a minute of clock skew allowed). Access does the login itself (one-time PIN,
 * Google, …); this makes sure nothing reaches the ops API around it.
 */
class AccessVerifier(
    private val settings: AccessSettings,
    private val keys: AccessKeys,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun verify(token: String): AccessCheck {
        val parts = token.split('.')
        if (parts.size != 3) return AccessCheck.Invalid("shape")
        val header = decode(parts[0], JwtHeader.serializer()) ?: return AccessCheck.Invalid("header")
        if (header.alg != "RS256" || header.kid == null) return AccessCheck.Invalid("alg")
        val claims = claims(parts[1]) ?: return AccessCheck.Invalid("claims")

        val key =
            when (val found = keyFor(header.kid)) {
                is KeyLookup.Found -> found.key
                KeyLookup.Unknown -> return AccessCheck.Invalid("kid")
                KeyLookup.Unavailable -> return AccessCheck.Unavailable
            }
        if (!signatureOk(key, parts)) return AccessCheck.Invalid("signature")
        return checkClaims(claims)
    }

    private sealed interface KeyLookup {
        data class Found(val key: PublicKey) : KeyLookup

        data object Unknown : KeyLookup

        data object Unavailable : KeyLookup
    }

    /** The key named [kid]: from the keys already read, else from a fresh read (Access rotates its keys). */
    private suspend fun keyFor(kid: String): KeyLookup {
        keys
            .keys(refresh = false)
            ?.firstOrNull { it.kid == kid }
            ?.toPublicKey()
            ?.let { return KeyLookup.Found(it) }
        val fresh = keys.keys(refresh = true) ?: return KeyLookup.Unavailable
        return fresh.firstOrNull { it.kid == kid }?.toPublicKey()?.let { KeyLookup.Found(it) } ?: KeyLookup.Unknown
    }

    private fun checkClaims(claims: Claims): AccessCheck {
        val now = clock.instant().epochSecond
        return when {
            settings.audience !in claims.audiences -> AccessCheck.Invalid("aud")
            claims.iss != settings.issuer -> AccessCheck.Invalid("iss")
            claims.exp == null || claims.exp <= now -> AccessCheck.Invalid("exp")
            claims.nbf != null && claims.nbf > now + SKEW.seconds -> AccessCheck.Invalid("nbf")
            claims.email.isNullOrBlank() -> AccessCheck.Invalid("email")
            else -> AccessCheck.Verified(claims.email.trim().lowercase())
        }
    }

    private fun signatureOk(key: PublicKey, parts: List<String>): Boolean =
        try {
            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(key)
            verifier.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
            verifier.verify(Base64.getUrlDecoder().decode(parts[2]))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

    private fun claims(part: String): Claims? {
        decode(part, ClaimsOneAudience.serializer())?.let {
            return Claims(listOfNotNull(it.aud), it.exp, it.nbf, it.iss, it.email)
        }
        return decode(part, ClaimsManyAudiences.serializer())?.let { Claims(it.aud, it.exp, it.nbf, it.iss, it.email) }
    }

    private fun <T> decode(part: String, serializer: KSerializer<T>): T? =
        try {
            CircasoJson.decodeFromString(serializer, String(Base64.getUrlDecoder().decode(part), Charsets.UTF_8))
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private companion object {
        val SKEW: Duration = Duration.ofSeconds(60)
    }
}

/** An RSA key from its JWK modulus and exponent; null for any other kind. */
internal fun AccessJwk.toPublicKey(): PublicKey? {
    if (kty != "RSA" || n == null || e == null) return null
    return runCatching {
        val decoder = Base64.getUrlDecoder()
        val spec = RSAPublicKeySpec(BigInteger(1, decoder.decode(n)), BigInteger(1, decoder.decode(e)))
        KeyFactory.getInstance("RSA").generatePublic(spec)
    }.getOrNull()
}
