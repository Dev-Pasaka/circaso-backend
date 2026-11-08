package com.circaso.opsgateway

import com.circaso.gateway.access.AccessJwk
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64

/** A local stand-in for a Cloudflare Access team: its own RSA key, and tokens signed the way Access signs them. */
class AccessTokens(val kid: String = "test-key", private val keys: KeyPair = generate()) {
    val team = "circaso-test.cloudflareaccess.com"
    val audience = "aud-0123456789abcdef"

    val jwk: AccessJwk
        get() {
            val public = keys.public as RSAPublicKey
            return AccessJwk(
                kid = kid,
                kty = "RSA",
                n = b64(public.modulus),
                e = b64(public.publicExponent),
                alg = "RS256",
            )
        }

    @Serializable
    private data class Header(val alg: String, val kid: String, val typ: String = "JWT")

    @Serializable
    private data class Claims(
        val aud: List<String>,
        val email: String,
        val exp: Long,
        val iat: Long,
        val nbf: Long,
        val iss: String,
        val type: String = "app",
    )

    fun token(
        email: String = "pasaka@circaso.com",
        audience: String = this.audience,
        issuer: String = "https://$team",
        expiresAt: Instant = Instant.now().plusSeconds(3_600),
        notBefore: Instant = Instant.now().minusSeconds(10),
        alg: String = "RS256",
        kid: String = this.kid,
    ): String {
        val header = enc(Json.encodeToString(Header.serializer(), Header(alg, kid)))
        val claims =
            Claims(
                aud = listOf(audience),
                email = email,
                exp = expiresAt.epochSecond,
                iat = Instant.now().epochSecond,
                nbf = notBefore.epochSecond,
                iss = issuer,
            )
        val body = enc(Json.encodeToString(Claims.serializer(), claims))
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keys.private)
        signer.update("$header.$body".toByteArray())
        return "$header.$body.${Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign())}"
    }

    private fun enc(json: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    private fun b64(value: BigInteger): String {
        val bytes = value.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        fun generate(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }
}
