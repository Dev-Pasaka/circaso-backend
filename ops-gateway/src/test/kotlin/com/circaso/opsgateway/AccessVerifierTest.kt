package com.circaso.opsgateway

import com.circaso.opsgateway.auth.AccessCheck
import com.circaso.opsgateway.auth.AccessKeys
import com.circaso.opsgateway.auth.AccessVerifier
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AccessVerifierTest {
    private val access = AccessTokens()
    private val settings = AccessSettings(access.team, access.audience)
    private val verifier = AccessVerifier(settings, AccessKeys { listOf(access.jwk) })

    @Test
    fun `a good token names the person, lowercased`() =
        runTest {
            val check = verifier.verify(access.token(email = "Pasaka@Circaso.com"))

            assertEquals(AccessCheck.Verified("pasaka@circaso.com"), check)
        }

    @Test
    fun `a token for another Access application is refused`() =
        runTest {
            val check = verifier.verify(access.token(audience = "someone-elses-app"))

            assertEquals(AccessCheck.Invalid("aud"), check)
        }

    @Test
    fun `an expired token is refused`() =
        runTest {
            val check = verifier.verify(access.token(expiresAt = Instant.now().minusSeconds(1)))

            assertEquals(AccessCheck.Invalid("exp"), check)
        }

    @Test
    fun `a token from another team is refused`() =
        runTest {
            val check = verifier.verify(access.token(issuer = "https://evil.cloudflareaccess.com"))

            assertEquals(AccessCheck.Invalid("iss"), check)
        }

    @Test
    fun `a token not valid yet is refused, with a minute of clock skew allowed`() =
        runTest {
            assertIs<AccessCheck.Verified>(verifier.verify(access.token(notBefore = Instant.now().plusSeconds(30))))
            assertEquals(
                AccessCheck.Invalid("nbf"),
                verifier.verify(access.token(notBefore = Instant.now().plusSeconds(600))),
            )
        }

    @Test
    fun `a token signed by another key is refused`() =
        runTest {
            val impostor = AccessTokens(kid = access.kid) // same key id, different private key

            assertEquals(AccessCheck.Invalid("signature"), verifier.verify(impostor.token()))
        }

    @Test
    fun `only RS256 is accepted`() =
        runTest {
            assertEquals(AccessCheck.Invalid("alg"), verifier.verify(access.token(alg = "none")))
            assertEquals(AccessCheck.Invalid("shape"), verifier.verify("not-a-jwt"))
        }

    @Test
    fun `an unknown key id makes the keys be read again, so a rotated key is picked up`() =
        runTest {
            val rotated = AccessTokens(kid = "new-key")
            val reads = AtomicInteger()
            val keys =
                AccessKeys { refresh ->
                    reads.incrementAndGet()
                    if (refresh) listOf(access.jwk, rotated.jwk) else listOf(access.jwk)
                }

            val check = AccessVerifier(settings, keys).verify(rotated.token())

            assertIs<AccessCheck.Verified>(check)
            assertEquals(2, reads.get())
        }

    @Test
    fun `certificates that can't be read make the check unavailable rather than invalid`() =
        runTest {
            val check = AccessVerifier(settings, AccessKeys { null }).verify(access.token())

            assertEquals(AccessCheck.Unavailable, check)
        }
}
