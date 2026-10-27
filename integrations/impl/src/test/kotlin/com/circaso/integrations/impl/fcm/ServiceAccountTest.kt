package com.circaso.integrations.impl.fcm

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.security.KeyPairGenerator
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The service-account file read into a typed class, and the OAuth assertion written from one. */
class ServiceAccountTest {
    @Serializable
    private data class Claims(
        val iss: String,
        val scope: String,
        val aud: String,
        val iat: Long,
        val exp: Long,
    )

    private val pem: String =
        run {
            val key =
                KeyPairGenerator
                    .getInstance("RSA")
                    .apply { initialize(2048) }
                    .generateKeyPair()
                    .private
            val body = Base64.getMimeEncoder().encodeToString(key.encoded)
            "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----\n"
        }

    private fun file(extra: String = "") =
        """{"type":"service_account","project_id":"circaso-dev",""" +
            """"client_email":"push@circaso-dev.iam.gserviceaccount.com",""" +
            """"private_key":${Json.encodeToString(String.serializer(), pem)}$extra}"""

    @Test
    fun `the file's fields are read, unknown ones ignored, and the token endpoint defaults to Google's`() {
        val account = ServiceAccount.parse(file(""","private_key_id":"abc""""))

        assertEquals("circaso-dev", account.projectId)
        assertEquals("push@circaso-dev.iam.gserviceaccount.com", account.clientEmail)
        assertEquals("https://oauth2.googleapis.com/token", account.tokenUri)
    }

    @Test
    fun `a file without a field it needs names that field`() {
        val withoutEmail = """{"project_id":"p","private_key":${Json.encodeToString(String.serializer(), pem)}}"""

        val missing = assertFailsWith<IllegalStateException> { ServiceAccount.parse(withoutEmail) }

        assertTrue("client_email" in missing.message!!, missing.message)
    }

    @Test
    fun `the assertion has the typed header and claims, numbers as numbers`() {
        val clock = Clock.fixed(Instant.parse("2026-10-27T10:00:00Z"), ZoneOffset.UTC)
        val account = ServiceAccount.parse(file(""","token_uri":"https://oauth2.example/token""""))

        val (header, claims, _) = account.assertion("scope-1", clock).split('.')
        val decode = { part: String -> String(Base64.getUrlDecoder().decode(part)) }

        assertEquals("""{"alg":"RS256","typ":"JWT"}""", decode(header))
        val now = clock.instant().epochSecond
        assertEquals(
            Claims(
                "push@circaso-dev.iam.gserviceaccount.com",
                "scope-1",
                "https://oauth2.example/token",
                now,
                now + 3600,
            ),
            Json.decodeFromString<Claims>(decode(claims)),
        )
        assertTrue("\"iat\":$now" in decode(claims))
    }
}
