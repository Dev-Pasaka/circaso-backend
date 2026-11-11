package com.circaso.identity

import com.circaso.identity.api.PinVaultOpened
import com.circaso.identity.api.Principal
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.presentation.controllers.vaultController
import com.circaso.integrations.api.EmailTemplate
import com.circaso.shared.decode
import com.circaso.web.CallPrincipal
import com.circaso.web.CallPrincipalKey
import com.circaso.web.installJson
import com.circaso.web.installStatusPages
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The PIN Vault (2026-11-11), V1 to V4 through their routes: one encrypted blob per account, versioned, and every
 * opening logged, announced by email, paused for (`identity.PinVaultOpened`) and capped at five an hour.
 */
class VaultTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val credentialId = "AQIDBAUGBwgJCgsMDQ4PEA"
    private val salt = base64(ByteArray(32) { it.toByte() })
    private val ciphertext = base64(ByteArray(120) { (it * 7).toByte() })
    private val algorithm = "prf-hkdf-sha256+aes-256-gcm/v1"

    @Serializable
    private data class Envelope<T>(val code: String? = null, val data: T? = null)

    @Serializable
    private data class Status(
        val exists: Boolean,
        val credentialId: String? = null,
        val version: Int? = null,
        val updatedAt: String? = null,
    )

    @Serializable
    private data class Vault(
        val credentialId: String,
        val salt: String,
        val ciphertext: String,
        val algorithm: String,
        val version: Int,
        val updatedAt: String,
    )

    private class Setup(val h: Harness, val me: Principal)

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private suspend fun ApplicationTestBuilder.serve(): Setup {
        val h = Harness()
        val me = h.principalOf(h.signUp("amina", Phone(), "+254712345678").session.accessToken)
        application {
            installJson()
            installStatusPages()
            intercept(ApplicationCallPipeline.Plugins) {
                call.attributes.put(
                    CallPrincipalKey,
                    CallPrincipal(me.userId, me.deviceId, me.sessionId.value, me.scope),
                )
            }
            routing { route("/v1") { vaultController(h.vaultService) } }
        }
        return Setup(h, me)
    }

    private fun body(
        credential: String = credentialId,
        saltValue: String = salt,
        cipher: String = ciphertext,
        algo: String = algorithm,
        expectedVersion: Int? = null,
    ): String {
        val expected = expectedVersion?.let { ""","expectedVersion":$it""" }.orEmpty()
        return """{"credentialId":"$credential","salt":"$saltValue",""" +
            """"ciphertext":"$cipher","algorithm":"$algo"$expected}"""
    }

    private suspend fun ApplicationTestBuilder.save(body: String): HttpResponse =
        client.put("/v1/vault") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun code(response: String) = json.decodeFromString<Envelope<Unit>>(response).code

    private fun status(response: String) = json.decodeFromString<Envelope<Status>>(response).data!!

    private fun vault(response: String) = json.decodeFromString<Envelope<Vault>>(response).data!!

    private fun Setup.logged() =
        h.store.log
            .filter { it.userId == me.userId }
            .map { it.type }

    private fun Setup.openings() = h.store.eventTypes().count { it == PinVaultOpened.TYPE }

    @Test
    fun `a first save is version 1 and the status shows it, without the blob`() =
        testApplication {
            val s = serve()

            val saved = save(body())

            assertEquals(HttpStatusCode.OK, saved.status)
            assertEquals(1, status(saved.bodyAsText()).version)
            val text = client.get("/v1/vault/status").bodyAsText()
            val shown = status(text)
            assertTrue(shown.exists)
            assertEquals(credentialId, shown.credentialId)
            assertEquals(1, shown.version)
            assertNotNull(shown.updatedAt)
            assertFalse(ciphertext in text, "the status never carries the ciphertext")
            assertEquals(listOf(SecurityEventType.VAULT_SAVED), s.logged().filter { it.name.startsWith("VAULT") })
            assertEquals(
                "Pixel",
                s.h.store.log
                    .last()
                    .deviceModel,
            )
        }

    @Test
    fun `with no vault the status says so, and reading it is not an opening`() =
        testApplication {
            val s = serve()

            val shown = status(client.get("/v1/vault/status").bodyAsText())
            save(body())
            repeat(7) { client.get("/v1/vault/status") }

            assertEquals(Status(exists = false), shown)
            assertEquals(0, s.openings())
            assertFalse(SecurityEventType.VAULT_OPENED in s.logged())
            assertFalse(EmailTemplate.PIN_VAULT_OPENED in s.h.email.sent)
            assertEquals(HttpStatusCode.OK, client.get("/v1/vault").status, "seven status reads used no opening")
        }

    @Test
    fun `each replace goes up one version, and the expected version must match`() =
        testApplication {
            val s = serve()
            save(body())

            assertEquals(2, status(save(body(cipher = base64(ByteArray(64)))).bodyAsText()).version)
            assertEquals(3, status(save(body(expectedVersion = 2)).bodyAsText()).version)

            val stale = save(body(cipher = base64(ByteArray(16)), expectedVersion = 2))
            assertEquals(HttpStatusCode.Conflict, stale.status)
            assertEquals("vault.version_conflict", code(stale.bodyAsText()))
            val kept = s.h.store.vaults[s.me.userId]!!
            assertEquals(3, kept.version)
            assertEquals(ciphertext, kept.ciphertext, "the refused save changed nothing")
            assertEquals(3, s.logged().count { it == SecurityEventType.VAULT_SAVED })
        }

    @Test
    fun `an expected version with no vault is a conflict too`() =
        testApplication {
            val s = serve()

            val response = save(body(expectedVersion = 1))

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("vault.version_conflict", code(response.bodyAsText()))
            assertNull(s.h.store.vaults[s.me.userId])
        }

    @Test
    fun `opening hands over the blob as saved, logs it, emails the owner and pauses payments`() =
        testApplication {
            val s = serve()
            save(body())

            val response = client.get("/v1/vault")

            assertEquals(HttpStatusCode.OK, response.status)
            val opened = vault(response.bodyAsText())
            assertEquals(Vault(credentialId, salt, ciphertext, algorithm, 1, opened.updatedAt), opened)
            val entry =
                s.h.store.log
                    .last()
            assertEquals(SecurityEventType.VAULT_OPENED, entry.type)
            assertEquals("Pixel", entry.deviceModel, "the log names the phone that opened it")
            val event =
                s.h.store.events
                    .single { it.type == PinVaultOpened.TYPE }
            assertEquals(PinVaultOpened(s.me.userId), event.decode<PinVaultOpened>())
            assertEquals(
                EmailTemplate.PIN_VAULT_OPENED,
                s.h.email.sent
                    .last(),
            )
            assertEquals(
                "vault-opened-${event.eventId}",
                s.h.email.keys
                    .last(),
                "one email per opening, even on retry",
            )
        }

    @Test
    fun `opening with no vault is vault not_found, and nothing is logged, paused or sent`() =
        testApplication {
            val s = serve()

            val response = client.get("/v1/vault")

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("vault.not_found", code(response.bodyAsText()))
            assertEquals(0, s.openings())
            assertFalse(SecurityEventType.VAULT_OPENED in s.logged())
            assertFalse(EmailTemplate.PIN_VAULT_OPENED in s.h.email.sent)
        }

    @Test
    fun `a sixth opening within the hour is rate limited, with nothing logged or paused for it`() =
        testApplication {
            val s = serve()
            save(body())

            repeat(5) { assertEquals(HttpStatusCode.OK, client.get("/v1/vault").status) }
            val sixth = client.get("/v1/vault")

            assertEquals(HttpStatusCode.TooManyRequests, sixth.status)
            assertEquals("request.rate_limited", code(sixth.bodyAsText()))
            assertEquals("2520", sixth.headers[HttpHeaders.RetryAfter])
            assertEquals(5, s.openings())
            assertEquals(5, s.logged().count { it == SecurityEventType.VAULT_OPENED })
            assertEquals(
                5,
                s.h.email.sent
                    .count { it == EmailTemplate.PIN_VAULT_OPENED },
            )
        }

    @Test
    fun `delete removes the vault and logs it once, and succeeds when there is none`() =
        testApplication {
            val s = serve()
            save(body())

            val first = client.delete("/v1/vault")
            val again = client.delete("/v1/vault")

            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals(HttpStatusCode.OK, again.status)
            assertNull(s.h.store.vaults[s.me.userId])
            assertEquals(1, s.logged().count { it == SecurityEventType.VAULT_REMOVED })
            assertEquals(Status(exists = false), status(client.get("/v1/vault/status").bodyAsText()))
            assertEquals("vault.not_found", code(client.get("/v1/vault").bodyAsText()))
            val fresh = save(body(expectedVersion = null))
            assertEquals(1, status(fresh.bodyAsText()).version, "a vault saved after a delete starts again at 1")
        }

    @Test
    fun `each malformed field has its own code, and nothing is stored`() =
        testApplication {
            val s = serve()
            val cases =
                listOf(
                    body(credential = "") to "vault.credential_id_invalid",
                    body(credential = "not base64url!") to "vault.credential_id_invalid",
                    body(credential = "A".repeat(1025)) to "vault.credential_id_invalid",
                    body(saltValue = base64(ByteArray(15))) to "vault.salt_invalid",
                    body(saltValue = base64(ByteArray(65))) to "vault.salt_invalid",
                    body(saltValue = "%%%") to "vault.salt_invalid",
                    body(cipher = "") to "vault.ciphertext_invalid",
                    body(cipher = base64(ByteArray(8 * 1024 + 1))) to "vault.ciphertext_invalid",
                    body(cipher = "not-base64*") to "vault.ciphertext_invalid",
                    body(algo = "") to "vault.algorithm_invalid",
                    body(algo = "a".repeat(65)) to "vault.algorithm_invalid",
                    body(algo = "aes gcm") to "vault.algorithm_invalid",
                    body(expectedVersion = 0) to "vault.expected_version_invalid",
                    """{"credentialId":"$credentialId","salt":"$salt"}""" to "request.malformed",
                )

            for ((request, expected) in cases) {
                val response = save(request)
                assertEquals(HttpStatusCode.BadRequest, response.status, request.take(120))
                assertEquals(expected, code(response.bodyAsText()), request.take(120))
            }
            assertNull(s.h.store.vaults[s.me.userId])
            assertEquals(HttpStatusCode.OK, save(body(cipher = base64(ByteArray(8 * 1024)))).status, "8 KiB is allowed")
        }
}
