package com.circaso.identity.data.security

import com.circaso.identity.api.SessionId
import com.circaso.identity.domain.ports.AccessClaims
import com.circaso.identity.domain.ports.AccessTokenVerifier
import com.circaso.identity.domain.ports.IssuedToken
import com.circaso.identity.domain.ports.PublicKey
import com.circaso.identity.domain.ports.TokenIssuer
import com.circaso.infrastructure.api.Secrets
import com.circaso.shared.CircasoJson
import com.circaso.shared.DeviceId
import com.circaso.shared.Scope
import com.circaso.shared.UserId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.security.PublicKey as JavaPublicKey

private val log = LoggerFactory.getLogger("com.circaso.identity.data.security.Tokens")
private val b64 = Base64.getUrlEncoder().withoutPadding()
private val b64Decoder = Base64.getUrlDecoder()

// An Ed25519 SubjectPublicKeyInfo is this 12-byte prefix followed by the 32-byte raw key.
private val ED25519_SPKI_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

private fun rawKey(spki: ByteArray): ByteArray = spki.copyOfRange(ED25519_SPKI_PREFIX.size, spki.size)

private fun publicKeyFromRaw(raw: ByteArray): JavaPublicKey =
    KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(ED25519_SPKI_PREFIX + raw))

private fun keyId(raw: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(raw).take(6).joinToString("") {
        "%02x".format(it)
    }

/** A JWT's header as we write it; the fields keep this order, so a token encodes to the same bytes as before. */
@Serializable
internal data class JwtHeader(val alg: String, val typ: String, val kid: String)

/**
 * The claims of an access token, in the order they are written: user, device, session, scope, session version, and
 * issue and expiry times in epoch seconds. Unknown claims are ignored on read.
 */
@Serializable
internal data class AccessTokenClaims(
    val sub: String,
    val did: String,
    val sid: String,
    val scp: String,
    val ver: Int,
    val iat: Long,
    val exp: Long,
)

private const val ALGORITHM = "EdDSA"

private fun <T> encodePart(serializer: KSerializer<T>, value: T): String =
    b64.encodeToString(CircasoJson.encodeToString(serializer, value).toByteArray())

private fun <T> decodePart(serializer: KSerializer<T>, part: String): T =
    CircasoJson.decodeFromString(serializer, String(b64Decoder.decode(part)))

/**
 * Access tokens: JWTs signed with an Ed25519 key held by identity, 15 minutes, with `sub` (user), `did` (device),
 * `sid` (session), `scp` (scope) and `ver` (session version). The key is a secret (`identity.signing-key`, PKCS#8
 * base64, with its raw public key in `identity.signing-public-key`, base64url); locally a throwaway key is made when none is set. A key being retired stays in the JWKS through
 * `identity.previous-public-key` (its raw public key, base64url), so tokens it signed still verify.
 *
 * TODO(decision: signing key storage) an environment secret for the pilot; which key store before real money moves.
 */
class Ed25519Tokens(
    secrets: Secrets,
    local: Boolean,
    private val clock: Clock,
    private val lifetime: Duration = Duration.ofMinutes(15),
) : TokenIssuer,
    AccessTokenVerifier {
    private val secretsPublic: String? = secrets.get("identity.signing-public-key")

    private val pair: KeyPair =
        secrets.get("identity.signing-key")?.let(::load) ?: run {
            check(local) { "Missing secret 'identity.signing-key'" }
            log.warn("No identity.signing-key set; using a throwaway key, so every token dies on restart")
            KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        }
    private val currentRaw = rawKey(pair.public.encoded)
    private val currentId = keyId(currentRaw)
    private val keys: Map<String, JavaPublicKey> =
        buildMap {
            put(currentId, pair.public)
            secrets.get("identity.previous-public-key")?.let { previous ->
                val raw = b64Decoder.decode(previous)
                put(keyId(raw), publicKeyFromRaw(raw))
            }
        }

    /** The JDK can't derive an Ed25519 public key from the private one, so the secret comes as a pair. */
    private fun load(privatePkcs8: String): KeyPair {
        val private =
            KeyFactory
                .getInstance(
                    "Ed25519",
                ).generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(privatePkcs8)))
        val publicRaw = b64Decoder.decode(secretsPublic ?: error("Missing secret 'identity.signing-public-key'"))
        return KeyPair(publicKeyFromRaw(publicRaw), private)
    }

    override fun issue(
        userId: UserId,
        deviceId: DeviceId,
        sessionId: SessionId,
        scope: Scope,
        sessionVersion: Int,
    ): IssuedToken {
        val now = clock.instant()
        val claims =
            AccessTokenClaims(
                sub = userId.toString(),
                did = deviceId.toString(),
                sid = sessionId.toString(),
                scp = scope.wire,
                ver = sessionVersion,
                iat = now.epochSecond,
                exp = now.plus(lifetime).epochSecond,
            )
        val signingInput =
            encodePart(JwtHeader.serializer(), JwtHeader(ALGORITHM, "JWT", currentId)) + "." +
                encodePart(AccessTokenClaims.serializer(), claims)
        val signature =
            Signature.getInstance("Ed25519").run {
                initSign(pair.private)
                update(signingInput.toByteArray())
                sign()
            }
        return IssuedToken("$signingInput.${b64.encodeToString(signature)}", lifetime.seconds)
    }

    override fun publicKeys(): List<PublicKey> =
        keys.map { (id, key) ->
            PublicKey(
                id,
                b64.encodeToString(rawKey(key.encoded)),
            )
        }

    override fun verify(token: String): AccessClaims? =
        runCatching {
            val parts = token.split('.')
            if (parts.size != 3) return null
            val header = decodePart(JwtHeader.serializer(), parts[0])
            if (header.alg != ALGORITHM) return null
            val key = keys[header.kid] ?: return null

            val ok =
                Signature.getInstance("Ed25519").run {
                    initVerify(key)
                    update("${parts[0]}.${parts[1]}".toByteArray())
                    verify(b64Decoder.decode(parts[2]))
                }
            if (!ok) return null

            val claims = decodePart(AccessTokenClaims.serializer(), parts[1])
            if (clock.instant().epochSecond >= claims.exp) return null

            AccessClaims(
                userId = UserId.parse(claims.sub) ?: return null,
                deviceId = DeviceId.parse(claims.did) ?: return null,
                sessionId = SessionId.parse(claims.sid) ?: return null,
                scope = Scope.fromWire(claims.scp) ?: return null,
                sessionVersion = claims.ver,
            )
        }.getOrNull()
}
