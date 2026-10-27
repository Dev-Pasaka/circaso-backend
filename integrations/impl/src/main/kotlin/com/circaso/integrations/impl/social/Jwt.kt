package com.circaso.integrations.impl.social

import com.circaso.shared.CircasoJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.booleanOrNull
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/** The header of a Google or Apple ID token: only the algorithm and the key id are read. */
@Serializable
internal data class IdTokenHeader(val alg: String? = null, val kid: String? = null)

/**
 * The claims of a Google or Apple ID token that sign-in reads; every other claim is ignored. A claim a provider leaves
 * out is null, and the caller rejects the token when it needs it.
 */
@Serializable
internal data class IdTokenClaims(
    val iss: String? = null,
    val sub: String? = null,
    /** A provider may send `aud` as one string or as a list; both read as a list. */
    @Serializable(with = AudienceSerializer::class) val aud: List<String> = emptyList(),
    val exp: Long? = null,
    val iat: Long? = null,
    val nonce: String? = null,
    val email: String? = null,
    /** Apple sends `email_verified` as a JSON boolean or as the string "true"; anything else is false. */
    @Serializable(with = LooseFlagSerializer::class)
    @SerialName("email_verified")
    val emailVerified: Boolean = false,
    val name: String? = null,
    val picture: String? = null,
)

/** Reads `aud` as a list whether the provider sent one string or several. */
internal object AudienceSerializer : JsonTransformingSerializer<List<String>>(ListSerializer(String.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonArray) element else JsonArray(listOf(element))
}

/** `true` for a JSON boolean `true` or the string "true", `false` for anything else. */
internal object LooseFlagSerializer : JsonTransformingSerializer<Boolean>(Boolean.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        val primitive = element as? JsonPrimitive ?: return JsonPrimitive(false)
        return JsonPrimitive(primitive.booleanOrNull ?: (primitive.content == "true"))
    }
}

/** A provider's JWKS document. */
@Serializable
private data class Jwks(val keys: List<Jwk>)

/** One key of a JWKS; only RSA keys with a key id, modulus and exponent are used. */
@Serializable
private data class Jwk(
    val kty: String? = null,
    val kid: String? = null,
    val n: String? = null,
    val e: String? = null,
)

/** A compact JWT split into its parts; nothing in it is trusted until [verifyRs256] has passed. */
class ParsedJwt private constructor(
    private val header: IdTokenHeader,
    internal val claims: IdTokenClaims,
    private val signingInput: ByteArray,
    private val signature: ByteArray,
) {
    val algorithm: String? get() = header.alg
    val keyId: String? get() = header.kid

    fun verifyRs256(key: PublicKey): Boolean =
        runCatching {
            Signature.getInstance("SHA256withRSA").run {
                initVerify(key)
                update(signingInput)
                verify(signature)
            }
        }.getOrDefault(false)

    companion object {
        private val decoder = Base64.getUrlDecoder()

        private fun <T> decodePart(serializer: KSerializer<T>, part: String): T =
            CircasoJson.decodeFromString(serializer, String(decoder.decode(part)))

        fun parse(token: String): ParsedJwt? {
            val parts = token.split('.')
            if (parts.size != 3 || token.length > 8192) return null
            return runCatching {
                ParsedJwt(
                    header = decodePart(IdTokenHeader.serializer(), parts[0]),
                    claims = decodePart(IdTokenClaims.serializer(), parts[1]),
                    signingInput = "${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII),
                    signature = decoder.decode(parts[2]),
                )
            }.getOrNull()
        }
    }
}

/** RSA public keys from a provider's JWKS document, by key id. Keys of other types, or missing a part, are skipped. */
fun parseJwks(json: String): Map<String, PublicKey> {
    val factory = KeyFactory.getInstance("RSA")
    val decoder = Base64.getUrlDecoder()
    return CircasoJson
        .decodeFromString(Jwks.serializer(), json)
        .keys
        .mapNotNull { jwk ->
            if (jwk.kty != "RSA") return@mapNotNull null
            val kid = jwk.kid ?: return@mapNotNull null
            val n = BigInteger(1, decoder.decode(jwk.n ?: return@mapNotNull null))
            val e = BigInteger(1, decoder.decode(jwk.e ?: return@mapNotNull null))
            kid to factory.generatePublic(RSAPublicKeySpec(n, e))
        }.toMap()
}
