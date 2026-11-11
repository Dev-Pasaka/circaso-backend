package com.circaso.integrations.impl.resend

import com.circaso.integrations.api.Email
import com.circaso.integrations.api.EmailTemplate
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.impl.FakeHttpClients
import com.circaso.integrations.impl.FakeHttpClients.Companion.json
import com.circaso.integrations.impl.text
import com.circaso.shared.Resource
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ResendEmailTest {
    /** The body as Resend reads it, for the one template with variables. */
    @Serializable
    private data class Sent(val from: String, val to: List<String>, val template: Template)

    @Serializable
    private data class Template(val id: String, val variables: Variables)

    @Serializable
    private data class Variables(
        @SerialName("DEVICE_MODEL") val deviceModel: String? = null,
    )

    private var status = 200
    private var reply = """{"id":"msg_123"}"""
    private var down = false

    private val clients = FakeHttpClients { _ -> if (down) throw IOException("down") else json(reply, status) }
    private val templates = EmailTemplate.entries.associateWith { "tpl_${it.name.lowercase()}" }
    private val email =
        ResendEmail(
            "re_key",
            "Circaso <no-reply@circaso.com>",
            templates,
            clients.named("resend") {
                baseUrl =
                    "https://api.resend.com"
            },
        )

    @Test
    fun `sends the stored template with its variables and returns the message id`() =
        runBlocking<Unit> {
            val result =
                email.send("sam@example.com", Email.NewPhoneSignIn("Pixel 8"))

            assertEquals("msg_123", (result as Resource.Success).data.messageId)
            val request = clients.last
            assertEquals("https://api.resend.com/emails", request.url.toString())
            assertEquals("Bearer re_key", request.headers[HttpHeaders.Authorization])
            assertEquals(
                "circaso-backend",
                request.headers[HttpHeaders.UserAgent],
                "Resend rejects a request with no User-Agent",
            )
            assertEquals(
                Sent(
                    "Circaso <no-reply@circaso.com>",
                    listOf("sam@example.com"),
                    Template("tpl_new_phone_sign_in", Variables("Pixel 8")),
                ),
                Json.decodeFromString<Sent>(request.text()),
            )
        }

    @Test
    fun `the PIN Vault notice names the phone that opened it, under its own template`() =
        runBlocking<Unit> {
            email.send("sam@example.com", Email.PinVaultOpened("iPhone 16"), idempotencyKey = "vault-opened-1")

            assertEquals(
                Template("tpl_pin_vault_opened", Variables("iPhone 16")),
                Json.decodeFromString<Sent>(clients.last.text()).template,
            )
        }

    @Test
    fun `a template with no id configured is refused without calling Resend`() =
        runBlocking<Unit> {
            val unpublished =
                ResendEmail(
                    "re_key",
                    "Circaso <no-reply@circaso.com>",
                    templates - EmailTemplate.PIN_VAULT_OPENED,
                    clients.named("resend") { baseUrl = "https://api.resend.com" },
                )
            val before = clients.requests.size

            val result = unpublished.send("sam@example.com", Email.PinVaultOpened("iPhone 16"))

            assertIs<Resource.Error>(result)
            assertEquals(before, clients.requests.size, "nothing was sent")
        }

    @Test
    fun `a template without variables sends an empty variables object`() =
        runBlocking<Unit> {
            email.send("sam@example.com", Email.RecoveryCompleted)

            assertEquals(
                """{"from":"Circaso <no-reply@circaso.com>","to":["sam@example.com"],""" +
                    """"template":{"id":"tpl_recovery_completed","variables":{}}}""",
                clients.last.text(),
            )
        }

    @Test
    fun `a reply with more fields than the id is read, one without an id is Unavailable`() =
        runBlocking<Unit> {
            reply = """{"id":"msg_9","object":"email"}"""
            assertEquals("msg_9", (email.send("x@example.com", Email.Welcome) as Resource.Success).data.messageId)

            reply = """{"object":"email"}"""
            assertIs<IntegrationError.Unavailable>((email.send("x@example.com", Email.Welcome) as Resource.Error).error)
        }

    @Test
    fun `the idempotency key is passed on so a retried welcome is delivered once`() =
        runBlocking<Unit> {
            email.send("sam@example.com", Email.Welcome, idempotencyKey = "welcome-123")
            assertEquals("welcome-123", clients.last.headers["Idempotency-Key"])
            email.send("sam@example.com", Email.Welcome)
            assertNull(clients.last.headers["Idempotency-Key"], "no key, no header")
        }

    @Test
    fun `a refused email is Rejected and a vendor outage is Unavailable`() =
        runBlocking<Unit> {
            status = 422
            reply = """{"message":"invalid"}"""
            assertIs<IntegrationError.Rejected>(
                (email.send("x@example.com", Email.RecoveryStarted) as Resource.Error).error,
            )
            for (code in listOf(403, 429, 503)) {
                status = code
                assertIs<IntegrationError.Unavailable>(
                    (email.send("x@example.com", Email.RecoveryStarted) as Resource.Error).error,
                    "status $code",
                )
            }
        }

    @Test
    fun `an unreachable vendor is Unavailable`() =
        runBlocking<Unit> {
            down = true
            assertIs<IntegrationError.Unavailable>(
                (email.send("x@example.com", Email.RecoveryCompleted) as Resource.Error).error,
            )
        }
}
