package com.circaso.integrations.impl.social

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.SocialIdentity
import com.circaso.integrations.api.SocialProvider
import com.circaso.integrations.api.SocialSignIn
import com.circaso.shared.Resource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.time.Duration
import java.util.Base64

/** What differs between the two providers: where the keys are, who signs, and who the token must be for. */
class ProviderSpec(val jwksUrl: String, val issuers: Set<String>, val audiences: Set<String>)

/** Apple's settings for revoking a sign-in when an account is deleted. Optional: sign-in works without them. */
class AppleRevocation(val teamId: String, val keyId: String, val clientId: String, val privateKeyPem: String)

/** Downloads a provider's JWKS. Replaced in tests. */
fun interface JwksFetcher {
    @Throws(IOException::class)
    fun fetch(url: String): String
}

/**
 * Verifies Google and Apple ID tokens ourselves, with no vendor SDK: the RS256 signature against the provider's published keys
 * (cached for an hour, fetched again once when a token names a key we have not seen), then issuer, audience, expiry and the
 * nonce we issued. A token that fails any of these is `Rejected`; a provider that cannot be reached is `Unavailable`.
 * Nothing from a token is logged.
 */
class GoogleAppleSignIn(
    private val google: ProviderSpec,
    private val apple: ProviderSpec,
    private val revocation: AppleRevocation? = null,
    private val fetcher: JwksFetcher = JwksFetcher { url ->
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (response.statusCode() != 200) throw IOException("JWKS answered ${response.statusCode()}")
        response.body()
    },
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
        val fresh = KeySet(withContext(Dispatchers.IO) { parseJwks(fetcher.fetch(spec.jwksUrl)) }, now)
        keys[spec.jwksUrl] = fresh
        fresh.keys[kid]
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
            val form = listOf("client_id" to settings.clientId, "client_secret" to secret, "token" to appleToken, "token_type_hint" to "refresh_token")
                .joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, StandardCharsets.UTF_8)}" }
            val request = HttpRequest.newBuilder(URI.create(APPLE_REVOKE_URL))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build()
            val response = withContext(Dispatchers.IO) { HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()) }
            when {
                response.statusCode() == 200 -> Resource.success(Unit)
                response.statusCode() in 400..499 -> Resource.error(IntegrationError.Rejected("Apple refused to revoke the sign-in."))
                else -> Resource.error(IntegrationError.Unavailable("Apple"))
            }
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
