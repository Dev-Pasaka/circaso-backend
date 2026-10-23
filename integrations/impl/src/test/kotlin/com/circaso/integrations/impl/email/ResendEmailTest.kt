package com.circaso.integrations.impl.email

import com.circaso.integrations.api.EmailTemplate
import com.circaso.integrations.api.IntegrationError
import com.circaso.shared.Resource
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ResendEmailTest {
    private var status = 200
    private var reply = """{"id":"msg_123"}"""
    private var authorization: String? = null
    private var userAgent: String? = null
    private var idempotency: String? = null
    private var received: String? = null

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/emails") { ex ->
            authorization = ex.requestHeaders.getFirst("Authorization")
            userAgent = ex.requestHeaders.getFirst("User-Agent")
            idempotency = ex.requestHeaders.getFirst("Idempotency-Key")
            received = ex.requestBody.readBytes().decodeToString()
            val bytes = reply.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }

    private val templates = EmailTemplate.entries.associateWith { "tpl_${it.name.lowercase()}" }
    private val email = ResendEmail("re_key", "Circaso <no-reply@circaso.com>", templates, "http://127.0.0.1:${server.address.port}")

    @AfterTest
    fun tearDown() = server.stop(0)

    @Test
    fun `sends the stored template with its variables and returns the message id`() = runTest {
        val result = email.send("sam@example.com", EmailTemplate.NEW_PHONE_SIGN_IN, mapOf("device" to "Pixel 8"))

        assertEquals("msg_123", (result as Resource.Success).data.messageId)
        assertEquals("Bearer re_key", authorization)
        assertEquals("circaso-backend", userAgent, "Resend rejects a request with no User-Agent")
        val body = Json.parseToJsonElement(received!!).jsonObject
        assertEquals("Circaso <no-reply@circaso.com>", body["from"]!!.jsonPrimitive.content)
        assertEquals("sam@example.com", body["to"]!!.jsonArray.single().jsonPrimitive.content)
        val template = body["template"]!!.jsonObject
        assertEquals("tpl_new_phone_sign_in", template["id"]!!.jsonPrimitive.content)
        assertEquals("Pixel 8", template["variables"]!!.jsonObject["device"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the idempotency key is passed on so a retried welcome is delivered once`() = runTest {
        email.send("sam@example.com", EmailTemplate.WELCOME, idempotencyKey = "welcome-123")
        assertEquals("welcome-123", idempotency)
        email.send("sam@example.com", EmailTemplate.WELCOME)
        assertEquals(null, idempotency, "no key, no header")
    }

    @Test
    fun `a refused email is Rejected and a vendor outage is Unavailable`() = runTest {
        status = 422
        reply = """{"message":"invalid"}"""
        assertIs<IntegrationError.Rejected>((email.send("x@example.com", EmailTemplate.RECOVERY_STARTED) as Resource.Error).error)

        status = 503
        assertIs<IntegrationError.Unavailable>((email.send("x@example.com", EmailTemplate.RECOVERY_STARTED) as Resource.Error).error)

        status = 403
        assertIs<IntegrationError.Unavailable>((email.send("x@example.com", EmailTemplate.RECOVERY_STARTED) as Resource.Error).error)

        status = 429
        assertIs<IntegrationError.Unavailable>((email.send("x@example.com", EmailTemplate.RECOVERY_STARTED) as Resource.Error).error)
    }

    @Test
    fun `an unreachable vendor is Unavailable`() = runTest {
        val port = server.address.port
        server.stop(0)
        val down = ResendEmail("k", "a@b.c", templates, "http://127.0.0.1:$port")
        assertIs<IntegrationError.Unavailable>((down.send("x@example.com", EmailTemplate.RECOVERY_COMPLETED) as Resource.Error).error)
    }
}
