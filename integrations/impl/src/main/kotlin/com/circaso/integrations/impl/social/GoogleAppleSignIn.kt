package com.circaso.integrations.impl.social

import com.circaso.infrastructure.api.HttpClients
import com.circaso.infrastructure.api.operation
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.SocialIdentity
import com.circaso.integrations.api.SocialProvider
import com.circaso.integrations.api.SocialSignIn
import com.circaso.shared.Resource
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.util.Base64

/** What differs between the two providers: where the keys are, who signs, and who the token must be for. */
class ProviderSpec(val jwksUrl: String, val issuers: Set<String>, val audiences: Set<String>)

/** Apple's settings for revoking a sign-in when an account is deleted. Optional: sign-in works without them. */
class AppleRevocation(val teamId: String, val keyId: String, val clientId: String, val privateKeyPem: String)

/**
 * Verifies Google and Apple ID tokens ourselves, with no vendor SDK: the RS256 signature against the provider's published keys
 * (cached for an hour, fetched again once when a token names a key we have not seen, through the shared `HttpClients.named("google")` and
 * `named("apple")` clients, so timeouts, retries, the circuit breaker and the vendor metrics apply), then issuer, audience, expiry and the
 * nonce we issued. A token that fails any of these is `Rejected`; a provider that cannot be reached is `Unavailable`.
 * Nothing from a token is logged.
 */
class GoogleAppleSignIn(
    private val google: ProviderSpec,
    private val apple: ProviderSpec,
    private val clients: HttpClients,
    private val revocation: AppleRevocation? = null,
    private val clock: Clock = Clock.systemUTC(),
) : SocialSignIn {
    private val log = LoggerFactory.getLogger(GoogleAppleSignIn::class.java)
    private val keys = HashMap<String, KeySet>()
    private val lock = Mutex()

    private class KeySet(val keys: Map<String, PublicKey>, val fetchedAt: Long)

    override suspend fun verify(provider: SocialProvider, idToken: String, nonce: String): Resource<SocialIdentity> {
        val spec = if (provider == SocialProvider.GOOGLE) google else apple
        val rejected = Resource.error(IntegrationError.Rejected("The sign-in token was not accepted."))
        if (spec.audiences.isEmpty() || nonce.isBlank()) return rejected

        val jwt = ParsedJwt.parse(idToken) ?: return rejected
        if (jwt.algorithm != "RS256") return rejected
        val kid = jwt.keyId ?: return rejected

        val key = try {
            keyFor(spec, kid)
        } catch (e: Exception) {
            log.warn("could not load {} signing keys", provider, e)
            return Resource.error(IntegrationError.Unavailable(provider.name.lowercase().replaceFirstChar { it.uppercase() }), e)
        } ?: return rejected
        if (!jwt.verifyRs256(key)) return rejected

        val now = clock.instant().epochSecond
        val exp = jwt.seconds("exp") ?: return rejected
        val iat = jwt.seconds("iat") ?: return rejected
        if (exp < now - LEEWAY || iat > now + LEEWAY) return rejected
        if (jwt.string("iss") !in spec.issuers) return rejected
        if (jwt.audiences().none { it in spec.audiences }) return rejected
        if (!nonceMatches(provider, jwt.string("nonce"), nonce)) return rejected

        val subject = jwt.string("sub")?.takeIf { it.isNotBlank() } ?: return rejected
        return Resource.success(
            SocialIdentity(
                provider = provider,
                subject = subject,
                email = jwt.string("email")?.takeIf { it.isNotBlank() },
                emailVerified = jwt.flag("email_verified"),
                displayName = jwt.string("name")?.takeIf { it.isNotBlank() },
                pictureUrl = jwt.string("picture")?.takeIf { it.startsWith("https://") },
            ),
        )
    }

    /** Google echoes the nonce as given. Apple's native flow gives it the SHA-256 of the nonce, so either form is accepted there. */
    private fun nonceMatches(provider: SocialProvider, claim: String?, nonce: String): Boolean {
        if (claim == null) return false
        val allowed = if (provider == SocialProvider.APPLE) listOf(nonce, sha256Hex(nonce)) else listOf(nonce)
        return allowed.any { MessageDigest.isEqual(it.toByteArray(), claim.toByteArray()) }
    }

    private suspend fun keyFor(spec: ProviderSpec, kid: String): PublicKey? = lock.withLock {
        val now = clock.instant().epochSecond
        val cached = keys[spec.jwksUrl]
        if (cached != null && now - cached.fetchedAt < KEYS_FRESH_FOR) cached.keys[kid]?.let { return it }
        // Unknown key id or stale list: fetch again, but not more than once a minute, so a flood of bad tokens cannot hammer the provider.
        if (cached != null && now - cached.fetchedAt < MIN_REFETCH && cached.keys[kid] == null) return null
        val fresh = KeySet(parseJwks(fetchJwks(spec)), now)
        keys[spec.jwksUrl] = fresh
        fresh.keys[kid]
    }

    /** The provider's key document. A non-200 answer is an error, so a bad day at the provider is `Unavailable` and never a cached empty list. */
    private suspend fun fetchJwks(spec: ProviderSpec): String {
        val client = clients.named(if (spec === google) "google" else "apple")
        val response = client.get(spec.jwksUrl) { operation("fetch_keys") }
        check(response.status.value == 200) { "keys answered ${response.status.value}" }
        return response.bodyAsText()
    }

    /**
     * Revokes the account's Sign in with Apple grant (Apple requires it when an account is deleted). [appleToken] is the refresh
     * token Apple returned for the user. Needs the team id, key id, client id and the `.p8` key; without them it reports
     * `Unavailable` so the caller can try again once they are configured.
     */
    // TODO(decision: where the Apple refresh token is kept and who exchanges the authorization code; account deletion does not call this yet)
    override suspend fun revokeApple(appleToken: String): Resource<Unit> {
        val settings = revocation ?: run {
            log.warn("Sign in with Apple revocation is not configured; the grant was not revoked")
            return Resource.error(IntegrationError.Unavailable("Apple"))
        }
        return try {
            val secret = clientSecret(settings)
            val form = Parameters.build {
                append("client_id", settings.clientId)
                append("client_secret", secret)
                append("token", appleToken)
                append("token_type_hint", "refresh_token")
            }
            val response = clients.named("apple").post(APPLE_REVOKE_URL) {
                operation("revoke_token")
                setBody(FormDataContent(form))
            }
            val status = response.status.value
            when {
                status == 200 -> Resource.success(Unit)
                status in 400..499 -> Resource.error(IntegrationError.Rejected("Apple refused to revoke the sign-in."))
                else -> Resource.error(IntegrationError.Unavailable("Apple"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("could not revoke the Apple sign-in", e)
            Resource.error(IntegrationError.Unavailable("Apple"), e)
        }
    }

    /** Apple's "client secret" is a short-lived ES256 JWT signed with the `.p8` key. */
    private fun clientSecret(settings: AppleRevocation): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val now = clock.instant().epochSecond
        val header = """{"alg":"ES256","kid":"${settings.keyId}"}"""
        val claims = """{"iss":"${settings.teamId}","iat":$now,"exp":${now + 600},"aud":"https://appleid.apple.com","sub":"${settings.clientId}"}"""
        val input = enc.encodeToString(header.toByteArray()) + "." + enc.encodeToString(claims.toByteArray())
        val der = Base64.getMimeDecoder().decode(
            settings.privateKeyPem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replace("\\n", "").replace("\\s".toRegex(), ""),
        )
        val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der))
        val signature = Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initSign(key)
            update(input.toByteArray())
            sign()
        }
        return input + "." + enc.encodeToString(signature)
    }

    private fun sha256Hex(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val GOOGLE_JWKS = "https://www.googleapis.com/oauth2/v3/certs"
        const val APPLE_JWKS = "https://appleid.apple.com/auth/keys"
        const val APPLE_REVOKE_URL = "https://appleid.apple.com/auth/revoke"
        val GOOGLE_ISSUERS = setOf("https://accounts.google.com", "accounts.google.com")
        val APPLE_ISSUERS = setOf("https://appleid.apple.com")
        private const val LEEWAY = 60L
        private const val KEYS_FRESH_FOR = 3600L
        private const val MIN_REFETCH = 60L
    }
}
