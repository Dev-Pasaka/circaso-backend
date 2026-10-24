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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
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
        val header =
            buildJsonObject {
                put("alg", "EdDSA")
                put("typ", "JWT")
                put("kid", currentId)
            }
        val payload =
            buildJsonObject {
                put("sub", userId.toString())
                put("did", deviceId.toString())
                put("sid", sessionId.toString())
                put("scp", scope.wire)
                put("ver", sessionVersion)
                put("iat", now.epochSecond)
                put("exp", now.plus(lifetime).epochSecond)
            }
        val signingInput =
            b64.encodeToString(header.toString().toByteArray()) + "." +
                b64.encodeToString(payload.toString().toByteArray())
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
            val header = CircasoJson.parseToJsonElement(String(b64Decoder.decode(parts[0]))) as JsonObject
            if ((header["alg"] as? JsonPrimitive)?.contentOrNull != "EdDSA") return null
            val key = keys[(header["kid"] as? JsonPrimitive)?.contentOrNull] ?: return null

            val ok =
                Signature.getInstance("Ed25519").run {
                    initVerify(key)
                    update("${parts[0]}.${parts[1]}".toByteArray())
                    verify(b64Decoder.decode(parts[2]))
                }
            if (!ok) return null

            val claims = CircasoJson.parseToJsonElement(String(b64Decoder.decode(parts[1]))) as JsonObject

            fun text(name: String) = (claims[name] as? JsonPrimitive)?.contentOrNull
            val expires = (claims["exp"] as? JsonPrimitive)?.longOrNull ?: return null
            if (clock.instant().epochSecond >= expires) return null

            AccessClaims(
                userId = UserId.parse(text("sub") ?: return null) ?: return null,
                deviceId = DeviceId.parse(text("did") ?: return null) ?: return null,
                sessionId = SessionId.parse(text("sid") ?: return null) ?: return null,
                scope = Scope.fromWire(text("scp") ?: return null) ?: return null,
                sessionVersion = (claims["ver"] as? JsonPrimitive)?.intOrNull ?: return null,
            )
        }.getOrNull()
}
