package com.circaso.identity

import com.circaso.identity.api.SessionId
import com.circaso.identity.data.security.Ed25519Tokens
import com.circaso.identity.domain.ports.AccessClaims
import com.circaso.infrastructure.api.Secrets
import com.circaso.shared.DeviceId
import com.circaso.shared.Scope
import com.circaso.shared.UserId
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Access tokens are typed classes now (decision 2026-10-27), but must stay byte for byte what the hand-built JSON
 * made, so tokens issued before the change still verify and a token issued now reads the same everywhere.
 */
class TokensTest {
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val now = Instant.parse("2026-10-27T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val rawPublic = pair.public.encoded.copyOfRange(12, 44)
    private val kid =
        MessageDigest
            .getInstance(
                "SHA-256",
            ).digest(rawPublic)
            .take(6)
            .joinToString("") { "%02x".format(it) }
    private val secrets =
        object : Secrets {
            override fun get(name: String): String? =
                when (name) {
                    "identity.signing-key" -> Base64.getEncoder().encodeToString(pair.private.encoded)
                    "identity.signing-public-key" -> b64.encodeToString(rawPublic)
                    else -> null
                }
        }
    private val tokens = Ed25519Tokens(secrets, local = false, clock = clock)

    private val userId = UserId.new()
    private val deviceId = DeviceId.new()
    private val sessionId = SessionId.new()

    /** The header and claims exactly as the code before the typed classes built them. */
    private fun legacyParts(expires: Instant): Pair<String, String> {
        val header =
            buildJsonObject {
                put("alg", "EdDSA")
                put("typ", "JWT")
                put("kid", kid)
            }
        val payload =
            buildJsonObject {
                put("sub", userId.toString())
                put("did", deviceId.toString())
                put("sid", sessionId.toString())
                put("scp", Scope.APP.wire)
                put("ver", 3)
                put("iat", now.epochSecond)
                put("exp", expires.epochSecond)
            }
        return header.toString() to payload.toString()
    }

    private fun sign(
        header: String,
        payload: String,
        key: PrivateKey = pair.private,
    ): String {
        val input = b64.encodeToString(header.toByteArray()) + "." + b64.encodeToString(payload.toByteArray())
        val signature =
            Signature.getInstance("Ed25519").run {
                initSign(key)
                update(input.toByteArray())
                sign()
            }
        return "$input.${b64.encodeToString(signature)}"
    }

    @Test
    fun `a token issued now is byte for byte the hand-built format`() {
        val issued = tokens.issue(userId, deviceId, sessionId, Scope.APP, 3)
        val (header, payload) = legacyParts(now.plusSeconds(issued.expiresInSeconds))
        val parts = issued.token.split('.')

        assertEquals(header, String(Base64.getUrlDecoder().decode(parts[0])))
        assertEquals(payload, String(Base64.getUrlDecoder().decode(parts[1])))
        assertEquals(sign(header, payload), issued.token)
    }

    @Test
    fun `a token issued and read back gives the same claims`() {
        val issued = tokens.issue(userId, deviceId, sessionId, Scope.ONBOARDING, 7)

        assertEquals(AccessClaims(userId, deviceId, sessionId, Scope.ONBOARDING, 7), tokens.verify(issued.token))
    }

    @Test
    fun `a token in the existing format verifies`() {
        val (header, payload) = legacyParts(now.plusSeconds(900))

        assertEquals(AccessClaims(userId, deviceId, sessionId, Scope.APP, 3), tokens.verify(sign(header, payload)))
    }

    @Test
    fun `an expired token, another algorithm, an unknown key or a missing claim does not verify`() {
        val (header, expired) = legacyParts(now)
        val (_, payload) = legacyParts(now.plusSeconds(900))
        val otherKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().private

        assertNull(tokens.verify(sign(header, expired)), "expired")
        assertNull(tokens.verify(sign(header.replace("EdDSA", "none"), payload)), "algorithm")
        assertNull(tokens.verify(sign(header.replace(kid, "000000000000"), payload)), "unknown key")
        assertNull(tokens.verify(sign(header, payload.replace(",\"ver\":3", ""))), "missing ver")
        assertNull(tokens.verify(sign(header, payload, otherKey)), "wrong signature")
    }
}
