package com.circaso.integrations.impl.social

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.SocialProvider
import com.circaso.shared.Resource
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GoogleAppleSignInTest {
    private val now = Instant.parse("2026-10-23T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val enc = Base64.getUrlEncoder().withoutPadding()

    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun jwks(kid: String, key: RSAPublicKey = pair.public as RSAPublicKey) =
        """{"keys":[{"kty":"RSA","kid":"$kid","alg":"RS256","use":"sig","n":"${enc.encodeToString(key.modulus.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())}","e":"${enc.encodeToString(key.publicExponent.toByteArray())}"}]}"""

    private var fetches = 0
    private var served = jwks("k1")
    private var down = false

    private fun verifier() = GoogleAppleSignIn(
        google = ProviderSpec("g", GoogleAppleSignIn.GOOGLE_ISSUERS, setOf("web-client", "android-client")),
        apple = ProviderSpec("a", GoogleAppleSignIn.APPLE_ISSUERS, setOf("com.circaso.app")),
        fetcher = JwksFetcher { url ->
            fetches++
            if (down) throw IOException("down $url")
            served
        },
        clock = clock,
    )

    private fun token(
        claims: Map<String, Any?>,
        kid: String? = "k1",
        alg: String = "RS256",
        signWith: java.security.PrivateKey = pair.private,
    ): String {
        fun json(v: Any?): String = when (v) {
            null -> "null"
            is Number, is Boolean -> v.toString()
            else -> "\"$v\""
        }
        val header = buildString { append("{\"alg\":\"$alg\""); if (kid != null) append(",\"kid\":\"$kid\""); append("}") }
        val payload = claims.entries.joinToString(",", "{", "}") { "\"${it.key}\":${json(it.value)}" }
        val input = enc.encodeToString(header.toByteArray()) + "." + enc.encodeToString(payload.toByteArray())
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(signWith); update(input.toByteArray()); sign() }
        return "$input.${enc.encodeToString(sig)}"
    }

    private fun google(extra: Map<String, Any?> = emptyMap()) = mapOf(
        "iss" to "https://accounts.google.com", "aud" to "web-client", "sub" to "1100", "iat" to now.epochSecond, "exp" to now.epochSecond + 3600,
        "nonce" to "N1", "email" to "amina@gmail.com", "email_verified" to true, "name" to "Amina Otieno", "picture" to "https://lh3.googleusercontent.com/a",
    ) + extra

    private fun verify(provider: SocialProvider, token: String, nonce: String = "N1", v: GoogleAppleSignIn = verifier()) = runBlocking { v.verify(provider, token, nonce) }
    private fun rejected(r: Resource<*>) = assertIs<IntegrationError.Rejected>((r as Resource.Error).error)

    @Test
    fun `a good Google token gives the identity`() {
        val id = (verify(SocialProvider.GOOGLE, token(google())) as Resource.Success).data
        assertEquals("1100", id.subject)
        assertEquals("amina@gmail.com", id.email)
        assertTrue(id.emailVerified)
        assertEquals("Amina Otieno", id.displayName)
        assertEquals("https://lh3.googleusercontent.com/a", id.pictureUrl)
    }

    @Test
    fun `an Apple token takes the raw nonce or its SHA-256, and email_verified as a string`() {
        val hashed = MessageDigest.getInstance("SHA-256").digest("N1".toByteArray()).joinToString("") { "%02x".format(it) }
        val apple = mapOf("iss" to "https://appleid.apple.com", "aud" to "com.circaso.app", "sub" to "00.abc", "iat" to now.epochSecond, "exp" to now.epochSecond + 3600,
            "email" to "x@privaterelay.appleid.com", "email_verified" to "true")
        val a = (verify(SocialProvider.APPLE, token(apple + ("nonce" to hashed))) as Resource.Success).data
        assertEquals("00.abc", a.subject)
        assertTrue(a.emailVerified)
        assertIs<Resource.Success<*>>(verify(SocialProvider.APPLE, token(apple + ("nonce" to "N1"))))
        rejected(verify(SocialProvider.APPLE, token(apple + ("nonce" to "other"))))
    }

    @Test
    fun `anything wrong with the token is rejected`() {
        rejected(verify(SocialProvider.GOOGLE, token(google(mapOf("aud" to "someone-elses-client")))))
        rejected(verify(SocialProvider.GOOGLE, token(google(mapOf("iss" to "https://evil.example")))))
        rejected(verify(SocialProvider.GOOGLE, token(google(mapOf("exp" to now.epochSecond - 600)))))
        rejected(verify(SocialProvider.GOOGLE, token(google(mapOf("iat" to now.epochSecond + 600)))))
        rejected(verify(SocialProvider.GOOGLE, token(google(mapOf("nonce" to "replayed")))))
        rejected(verify(SocialProvider.GOOGLE, token(google() - "nonce")))
        rejected(verify(SocialProvider.GOOGLE, token(google() - "sub")))
        rejected(verify(SocialProvider.GOOGLE, token(google(), signWith = other.private)))
        rejected(verify(SocialProvider.GOOGLE, token(google(), alg = "none")))
        rejected(verify(SocialProvider.GOOGLE, token(google(), alg = "HS256")))
        rejected(verify(SocialProvider.GOOGLE, token(google(), kid = null)))
        rejected(verify(SocialProvider.GOOGLE, "not.a.jwt"))
        rejected(verify(SocialProvider.GOOGLE, ""))
        rejected(verify(SocialProvider.GOOGLE, token(google()), nonce = ""))
    }

    @Test
    fun `a token for Apple is not accepted as Google's, and the other way round`() {
        rejected(verify(SocialProvider.APPLE, token(google())))
        val apple = mapOf("iss" to "https://appleid.apple.com", "aud" to "com.circaso.app", "sub" to "s", "iat" to now.epochSecond, "exp" to now.epochSecond + 3600, "nonce" to "N1")
        rejected(verify(SocialProvider.GOOGLE, token(apple)))
    }

    @Test
    fun `a provider with no client id configured accepts nothing`() {
        val none = GoogleAppleSignIn(ProviderSpec("g", GoogleAppleSignIn.GOOGLE_ISSUERS, emptySet()), ProviderSpec("a", GoogleAppleSignIn.APPLE_ISSUERS, emptySet()), fetcher = { served }, clock = clock)
        rejected(verify(SocialProvider.GOOGLE, token(google()), v = none))
    }

    @Test
    fun `keys are cached, fetched again for a new key id, and a flood of unknown ones does not hammer the provider`() {
        val v = verifier()
        verify(SocialProvider.GOOGLE, token(google()), v = v)
        verify(SocialProvider.GOOGLE, token(google()), v = v)
        assertEquals(1, fetches, "the second token used the cached keys")

        served = jwks("k2")
        rejected(verify(SocialProvider.GOOGLE, token(google(), kid = "k2"), v = v))
        assertEquals(1, fetches, "a new key id inside a minute does not refetch")

        val later = GoogleAppleSignIn(
            ProviderSpec("g", GoogleAppleSignIn.GOOGLE_ISSUERS, setOf("web-client")), ProviderSpec("a", GoogleAppleSignIn.APPLE_ISSUERS, setOf("x")),
            fetcher = { fetches++; served }, clock = clock,
        )
        fetches = 0
        assertIs<Resource.Success<*>>(verify(SocialProvider.GOOGLE, token(google(), kid = "k2"), v = later), "a rotated key is picked up")
        assertEquals(1, fetches)
    }

    @Test
    fun `an unreachable provider is Unavailable, not a bad token`() {
        down = true
        assertIs<IntegrationError.Unavailable>((verify(SocialProvider.GOOGLE, token(google())) as Resource.Error).error)
    }

    @Test
    fun `revoking Apple without its key is Unavailable so deletion can retry later`() = runBlocking {
        assertIs<IntegrationError.Unavailable>((verifier().revokeApple("refresh") as Resource.Error).error)
    }
}
