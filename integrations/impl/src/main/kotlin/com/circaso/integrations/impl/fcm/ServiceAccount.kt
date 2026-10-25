package com.circaso.integrations.impl.fcm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.util.Base64

/** A Google service account, from the JSON file the Firebase console hands out. The private key never leaves this object. */
class ServiceAccount(
    val projectId: String,
    val clientEmail: String,
    val tokenUri: String,
    private val privateKey: PrivateKey,
) {
    /** A signed `urn:ietf:params:oauth:grant-type:jwt-bearer` assertion for [scope], valid for an hour. */
    fun assertion(scope: String, clock: Clock): String {
        val now = clock.instant().epochSecond
        val header = """{"alg":"RS256","typ":"JWT"}"""
        val claims = """{"iss":"$clientEmail","scope":"$scope","aud":"$tokenUri","iat":$now,"exp":${now + 3600}}"""
        val input = "${encode(header.toByteArray())}.${encode(claims.toByteArray())}"
        val signature =
            Signature.getInstance("SHA256withRSA").run {
                initSign(privateKey)
                update(input.toByteArray(Charsets.US_ASCII))
                sign()
            }
        return "$input.${encode(signature)}"
    }

    companion object {
        private fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        /** [raw] is the JSON file's text, or that text in base64 (easier to keep in one environment line). */
        fun parse(raw: String): ServiceAccount {
            val text = raw.trim().let { if (it.startsWith("{")) it else String(Base64.getDecoder().decode(it)) }
            val json = Json.parseToJsonElement(text).jsonObject

            fun field(name: String) =
                json[name]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("The FCM service account has no '$name'")
            val pem =
                field("private_key")
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replace(Regex("\\s"), "")
            return ServiceAccount(
                projectId = field("project_id"),
                clientEmail = field("client_email"),
                tokenUri = json["token_uri"]?.jsonPrimitive?.content ?: "https://oauth2.googleapis.com/token",
                privateKey =
                    KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem))),
            )
        }
    }
}
