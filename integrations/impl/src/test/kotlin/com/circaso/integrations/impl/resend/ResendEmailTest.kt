package com.circaso.integrations.impl.resend

import com.circaso.integrations.api.EmailTemplate
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.impl.FakeHttpClients
import com.circaso.integrations.impl.FakeHttpClients.Companion.json
import com.circaso.integrations.impl.text
import com.circaso.shared.Resource
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ResendEmailTest {
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
                email.send(
                    "sam@example.com",
                    EmailTemplate.NEW_PHONE_SIGN_IN,
                    mapOf("DEVICE_MODEL" to "Pixel 8"),
                )

            assertEquals("msg_123", (result as Resource.Success).data.messageId)
            val request = clients.last
            assertEquals("https://api.resend.com/emails", request.url.toString())
            assertEquals("Bearer re_key", request.headers[HttpHeaders.Authorization])
            assertEquals(
                "circaso-backend",
                request.headers[HttpHeaders.UserAgent],
                "Resend rejects a request with no User-Agent",
            )
            val body = Json.parseToJsonElement(request.text()).jsonObject
            assertEquals("Circaso <no-reply@circaso.com>", body["from"]!!.jsonPrimitive.content)
            assertEquals(
                "sam@example.com",
                body["to"]!!
                    .jsonArray
                    .single()
                    .jsonPrimitive.content,
            )
            val template = body["template"]!!.jsonObject
            assertEquals("tpl_new_phone_sign_in", template["id"]!!.jsonPrimitive.content)
            assertEquals("Pixel 8", template["variables"]!!.jsonObject["DEVICE_MODEL"]!!.jsonPrimitive.content)
        }

    @Test
    fun `the idempotency key is passed on so a retried welcome is delivered once`() =
        runBlocking<Unit> {
            email.send("sam@example.com", EmailTemplate.WELCOME, idempotencyKey = "welcome-123")
            assertEquals("welcome-123", clients.last.headers["Idempotency-Key"])
            email.send("sam@example.com", EmailTemplate.WELCOME)
            assertNull(clients.last.headers["Idempotency-Key"], "no key, no header")
        }

    @Test
    fun `a refused email is Rejected and a vendor outage is Unavailable`() =
        runBlocking<Unit> {
            status = 422
            reply = """{"message":"invalid"}"""
            assertIs<IntegrationError.Rejected>(
                (email.send("x@example.com", EmailTemplate.RECOVERY_STARTED) as Resource.Error).error,
            )
            for (code in listOf(403, 429, 503)) {
                status = code
                assertIs<IntegrationError.Unavailable>(
                    (email.send("x@example.com", EmailTemplate.RECOVERY_STARTED) as Resource.Error).error,
                    "status $code",
                )
            }
        }

    @Test
    fun `an unreachable vendor is Unavailable`() =
        runBlocking<Unit> {
            down = true
            assertIs<IntegrationError.Unavailable>(
                (email.send("x@example.com", EmailTemplate.RECOVERY_COMPLETED) as Resource.Error).error,
            )
        }
}
