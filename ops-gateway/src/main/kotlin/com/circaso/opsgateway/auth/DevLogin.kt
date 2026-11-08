package com.circaso.opsgateway.auth

import com.circaso.shared.CircasoJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** What a dev login token says: which staff member, until when. Signed, not encrypted: it holds nothing secret. */
@Serializable
data class DevLoginClaims(
    val typ: String = TYPE,
    val sub: String,
    val email: String,
    val iat: Long,
    val exp: Long,
) {
    companion object {
        const val TYPE = "circaso-ops-dev"
    }
}

/**
 * The sandbox-only dev login (decision 2026-11-08): with `OPS_DEV_LOGIN_SECRET` set (never in production, where the
 * process refuses to start), a staff member who knows the secret gets a short-lived bearer token for the portal. The
 * token is HMAC-SHA256 over its claims with a key derived from the secret, so changing the secret ends every token.
 * The person must still be on the staff list, checked on every request like an Access sign-in.
 */
class DevLogin(secret: String, private val ttl: Duration, private val clock: Clock = Clock.systemUTC()) {
    private val key: ByteArray =
        MessageDigest
            .getInstance(
                "SHA-256",
            ).digest("circaso-ops-dev-login:$secret".toByteArray())
    private val secretDigest: ByteArray = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray())

    /** Compares in constant time, over digests so the length leaks nothing either. */
    fun secretMatches(given: String): Boolean =
        MessageDigest.isEqual(secretDigest, MessageDigest.getInstance("SHA-256").digest(given.toByteArray()))

    fun issue(staffId: UUID, email: String): IssuedDevToken {
        val now = clock.instant()
        val expires = now.plus(ttl)
        val claims =
            DevLoginClaims(sub = staffId.toString(), email = email, iat = now.epochSecond, exp = expires.epochSecond)
        val body = encoder.encodeToString(CircasoJson.encodeToString(DevLoginClaims.serializer(), claims).toByteArray())
        return IssuedDevToken("$body.${sign(body)}", expires)
    }

    /** The claims of a good, unexpired token, else null. */
    fun verify(token: String): DevLoginClaims? {
        val dot = token.indexOf('.')
        if (dot <= 0 || dot != token.lastIndexOf('.')) return null
        val body = token.substring(0, dot)
        val signature = token.substring(dot + 1)
        if (!MessageDigest.isEqual(sign(body).toByteArray(), signature.toByteArray())) return null
        val claims =
            try {
                CircasoJson.decodeFromString(DevLoginClaims.serializer(), String(Base64.getUrlDecoder().decode(body)))
            } catch (_: SerializationException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            }
        if (claims.typ != DevLoginClaims.TYPE || claims.exp <= clock.instant().epochSecond) return null
        return claims
    }

    private fun sign(body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return encoder.encodeToString(mac.doFinal(body.toByteArray()))
    }

    private companion object {
        val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    }
}

data class IssuedDevToken(val token: String, val expiresAt: Instant) {
    override fun toString(): String = "IssuedDevToken(***, $expiresAt)"
}
