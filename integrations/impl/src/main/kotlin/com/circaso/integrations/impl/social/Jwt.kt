package com.circaso.integrations.impl.social

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/** A compact JWT split into its parts; nothing in it is trusted until [verifyRs256] has passed. */
class ParsedJwt private constructor(
    val header: JsonObject,
    val claims: JsonObject,
    private val signingInput: ByteArray,
    private val signature: ByteArray,
) {
    val algorithm: String? get() = header["alg"]?.jsonPrimitive?.contentOrNull
    val keyId: String? get() = header["kid"]?.jsonPrimitive?.contentOrNull

    fun string(name: String): String? = (claims[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** `true` for a JSON boolean or the string "true": Apple sends `email_verified` both ways. */
    fun flag(name: String): Boolean =
        (claims[name] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull == "true") } ?: false

    fun seconds(name: String): Long? = (claims[name] as? JsonPrimitive)?.longOrNull

    /** The `aud` claim, which a provider may send as one string or as a list. */
    fun audiences(): Set<String> =
        when (val aud = claims["aud"]) {
            is JsonPrimitive -> setOf(aud.content)
            null -> emptySet()
            else -> runCatching { aud.jsonArray.map { it.jsonPrimitive.content }.toSet() }.getOrDefault(emptySet())
        }

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

        fun parse(token: String): ParsedJwt? {
            val parts = token.split('.')
            if (parts.size != 3 || token.length > 8192) return null
            return runCatching {
                ParsedJwt(
                    header = Json.parseToJsonElement(String(decoder.decode(parts[0]))).jsonObject,
                    claims = Json.parseToJsonElement(String(decoder.decode(parts[1]))).jsonObject,
                    signingInput = "${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII),
                    signature = decoder.decode(parts[2]),
                )
            }.getOrNull()
        }
    }
}

/** RSA public keys from a provider's JWKS document, by key id. Keys of other types are skipped. */
fun parseJwks(json: String): Map<String, PublicKey> {
    val factory = KeyFactory.getInstance("RSA")
    val decoder = Base64.getUrlDecoder()
    return Json
        .parseToJsonElement(json)
        .jsonObject["keys"]!!
        .jsonArray
        .mapNotNull { element ->
            val jwk = element.jsonObject
            if (jwk["kty"]?.jsonPrimitive?.content != "RSA") return@mapNotNull null
            val kid = jwk["kid"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val n = BigInteger(1, decoder.decode(jwk["n"]!!.jsonPrimitive.content))
            val e = BigInteger(1, decoder.decode(jwk["e"]!!.jsonPrimitive.content))
            kid to factory.generatePublic(RSAPublicKeySpec(n, e))
        }.toMap()
}
