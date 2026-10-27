package com.circaso.integrations.impl.social

import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Google and Apple ID tokens and key documents read into typed classes, with the shapes each provider sends. */
class JwtTest {
    private val enc = Base64.getUrlEncoder().withoutPadding()

    private fun jwt(header: String, claims: String) =
        enc.encodeToString(header.toByteArray()) + "." + enc.encodeToString(claims.toByteArray()) + ".c2ln"

    @Test
    fun `the header gives the algorithm and key id, and claims read with unknown ones ignored`() {
        val parsed =
            ParsedJwt.parse(
                jwt(
                    """{"alg":"RS256","kid":"k1","typ":"JWT"}""",
                    """{"iss":"https://accounts.google.com","aud":"web","sub":"1100","iat":10,"exp":20,""" +
                        """"nonce":"N1","email":"a@b.c","email_verified":true,"name":"Amina","at_hash":"x","azp":"y"}""",
                ),
            )!!

        assertEquals("RS256", parsed.algorithm)
        assertEquals("k1", parsed.keyId)
        assertEquals(
            IdTokenClaims(
                iss = "https://accounts.google.com",
                sub = "1100",
                aud = listOf("web"),
                exp = 20,
                iat = 10,
                nonce = "N1",
                email = "a@b.c",
                emailVerified = true,
                name = "Amina",
            ),
            parsed.claims,
        )
    }

    @Test
    fun `aud reads as a list from one string or several, and email_verified from a boolean or a string`() {
        fun claims(json: String) = ParsedJwt.parse(jwt("""{"alg":"RS256"}""", json))!!.claims

        assertEquals(listOf("a", "b"), claims("""{"aud":["a","b"]}""").aud)
        assertEquals(listOf("a"), claims("""{"aud":"a"}""").aud)
        assertTrue(claims("""{"email_verified":"true"}""").emailVerified)
        assertFalse(claims("""{"email_verified":"false"}""").emailVerified)
        assertFalse(claims("""{"email_verified":{}}""").emailVerified)
        assertFalse(claims("""{}""").emailVerified)
        assertNull(claims("""{}""").sub)
    }

    @Test
    fun `a token that is not three parts or not JSON does not parse`() {
        assertNull(ParsedJwt.parse("a.b"))
        assertNull(ParsedJwt.parse(enc.encodeToString("nope".toByteArray()) + ".e30.c2ln"))
    }

    @Test
    fun `the key document keeps only complete RSA keys`() {
        val key =
            KeyPairGenerator
                .getInstance(
                    "RSA",
                ).apply { initialize(2048) }
                .generateKeyPair()
                .public as RSAPublicKey
        val n = enc.encodeToString(key.modulus.toByteArray())
        val e = enc.encodeToString(key.publicExponent.toByteArray())

        val keys =
            parseJwks(
                """{"keys":[{"kty":"RSA","kid":"k1","n":"$n","e":"$e","use":"sig"},""" +
                    """{"kty":"EC","kid":"k2","x":"a","y":"b"},{"kty":"RSA","kid":"k3","e":"$e"},""" +
                    """{"kty":"RSA","n":"$n","e":"$e"}]}""",
            )

        assertEquals(setOf("k1"), keys.keys)
    }
}
