package com.circaso.integrations.impl.apns

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The APNs provider token: ES256 over P-256, raw R || S, cached 50 minutes, never refreshed within 20. */
class ApnsProviderTokenTest {
    private val pair: KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** What `INTEGRATIONS_APNS_KEY` holds: base64 of the `.p8` file. */
    private val p8Base64: String =
        run {
            val pem =
                "-----BEGIN PRIVATE KEY-----\n" +
                    Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.private.encoded) +
                    "\n-----END PRIVATE KEY-----\n"
            Base64.getEncoder().encodeToString(pem.toByteArray())
        }

    private class MovableClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?) = this

        override fun instant() = now
    }

    private val start = Instant.parse("2026-11-08T10:00:00Z")

    private fun tokens(clock: Clock = MovableClock(start)) =
        ApnsProviderToken("ABC123DEFG", "TEAM123456", ApnsProviderToken.parseKey(p8Base64), clock)

    private fun part(jwt: String, index: Int) =
        Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(jwt.split('.')[index]))).jsonObject

    @Test
    fun `the token is an ES256 JWT with the key id, the team and when it was made`() {
        val jwt = tokens().current()

        val header = part(jwt, 0)
        assertEquals("ES256", header["alg"]!!.jsonPrimitive.content)
        assertEquals("ABC123DEFG", header["kid"]!!.jsonPrimitive.content)
        val claims = part(jwt, 1)
        assertEquals("TEAM123456", claims["iss"]!!.jsonPrimitive.content)
        assertEquals(start.epochSecond, claims["iat"]!!.jsonPrimitive.long)
        assertEquals(setOf("iss", "iat"), claims.keys)
    }

    @Test
    fun `the signature is raw R and S, 64 bytes, and verifies with the public key`() {
        val jwt = tokens().current()
        val (header, claims, signature) = jwt.split('.')
        val raw = Base64.getUrlDecoder().decode(signature)

        assertEquals(64, raw.size, "JWS ES256 is R || S, not DER")
        val verified =
            Signature.getInstance("SHA256withECDSAinP1363Format").run {
                initVerify(pair.public)
                update("$header.$claims".toByteArray(Charsets.US_ASCII))
                verify(raw)
            }
        assertTrue(verified)
        // and not a signature over anything else
        val tampered =
            Signature.getInstance("SHA256withECDSAinP1363Format").run {
                initVerify(pair.public)
                update("$header.x$claims".toByteArray(Charsets.US_ASCII))
                verify(raw)
            }
        assertTrue(!tampered)
    }

    @Test
    fun `the token is reused for 50 minutes, then made again`() {
        val clock = MovableClock(start)
        val tokens = tokens(clock)
        val first = tokens.current()

        clock.now = start.plusSeconds(49 * 60L)
        assertEquals(first, tokens.current())

        clock.now = start.plusSeconds(50 * 60L)
        val second = tokens.current()
        assertNotEquals(first, second)
        assertEquals(clock.now.epochSecond, part(second, 1)["iat"]!!.jsonPrimitive.long)
    }

    @Test
    fun `an expired answer drops the token only once it is 20 minutes old, as Apple allows`() {
        val clock = MovableClock(start)
        val tokens = tokens(clock)
        val first = tokens.current()

        clock.now = start.plusSeconds(10 * 60L)
        tokens.expired()
        assertEquals(first, tokens.current(), "a new token within 20 minutes would be refused")

        clock.now = start.plusSeconds(20 * 60L)
        tokens.expired()
        assertNotEquals(first, tokens.current())
    }

    @Test
    fun `the key reads as base64 of the p8 file or as its PEM text, and anything else stops startup`() {
        val pem = String(Base64.getDecoder().decode(p8Base64))
        assertEquals(pair.private, ApnsProviderToken.parseKey(pem))
        assertEquals(pair.private, ApnsProviderToken.parseKey(pem.replace("\n", "\\n")))
        assertFailsWith<Exception> {
            ApnsProviderToken.parseKey(
                Base64.getEncoder().encodeToString("nope".toByteArray()),
            )
        }
    }

    @Test
    fun `the key never prints`() {
        assertTrue("PRIVATE" !in tokens().toString())
    }
}
