package com.circaso.infrastructure.impl.http

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.circaso.infrastructure.api.CircuitOpenException
import com.circaso.infrastructure.api.IDEMPOTENCY_KEY_HEADER
import com.circaso.infrastructure.api.operation
import com.circaso.observability.api.Metrics
import com.circaso.observability.api.Telemetry
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.net.ConnectException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VendorHttpClientsTest {

    private class Recording : Telemetry {
        val records = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
        override fun count(metric: String, labels: Map<String, String>, by: Long) = Unit
        override fun record(metric: String, value: Double, labels: Map<String, String>) { records += metric to labels }
        override fun gauge(metric: String, labels: Map<String, String>, supplier: () -> Double) = Unit
        override suspend fun <T> span(name: String, attributes: Map<String, String>, block: suspend () -> T): T = block()
        override fun traceparent(): String? = null
    }

    private val calls = CopyOnWriteArrayList<HttpRequestData>()
    private val telemetry = Recording()
    private val logs = ListAppender<ILoggingEvent>()
    private val vendorLog = LoggerFactory.getLogger("com.circaso.infrastructure.impl.http.Vendor") as Logger

    @BeforeTest
    fun listen() {
        logs.start()
        vendorLog.addAppender(logs)
        vendorLog.level = ch.qos.logback.classic.Level.DEBUG
    }

    @AfterTest
    fun stop() {
        vendorLog.detachAppender(logs)
    }

    /** A client whose engine answers from [script] in turn; the last step repeats. */
    private fun clients(vararg script: MockRequestHandleScope.(HttpRequestData) -> Any): VendorHttpClients {
        val engine = MockEngine { request ->
            calls += request
            val step = script[(calls.size - 1).coerceAtMost(script.size - 1)]
            @Suppress("UNCHECKED_CAST")
            step(request) as io.ktor.client.request.HttpResponseData
        }
        return VendorHttpClients(telemetry, { engine }, retryBaseMillis = 1)
    }

    private fun MockRequestHandleScope.ok() = respond("ok", HttpStatusCode.OK)
    private fun MockRequestHandleScope.status(code: HttpStatusCode, vararg headers: Pair<String, String>) =
        respond("", code, headersOf(*headers.map { it.first to listOf(it.second) }.toTypedArray()))

    private fun timeout(): MockRequestHandleScope.(HttpRequestData) -> Any = { throw HttpRequestTimeoutException("http://vendor/x", 1000) }

    private suspend fun HttpResponse.text() = bodyAsText()

    // ---- the retry table ---------------------------------------------------------------------------------

    @Test
    fun `a connection that failed before reaching the vendor is retried, even for a POST without a key`() = runBlocking {
        val client = clients({ throw ConnectException("refused") }, { throw ConnectTimeoutException("slow") }, { ok() }).named("v")

        val response = client.post("http://vendor/sms") { setBody("hello") }

        assertEquals("ok", response.text())
        assertEquals(3, calls.size)
    }

    @Test
    fun `429 and 503 are retried, and 429 waits for Retry-After`() = runBlocking {
        val client = clients({ status(HttpStatusCode.TooManyRequests, HttpHeaders.RetryAfter to "1") }, { status(HttpStatusCode.ServiceUnavailable) }, { ok() }).named("v")

        val started = System.currentTimeMillis()
        val response = client.get("http://vendor/x")
        val waited = System.currentTimeMillis() - started

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(3, calls.size)
        assertTrue(waited >= 900, "the first retry must wait out Retry-After: 1 (waited ${waited}ms)")
    }

    @Test
    fun `other server errors and client errors are not retried`() = runBlocking {
        for (code in listOf(HttpStatusCode.InternalServerError, HttpStatusCode.BadGateway, HttpStatusCode.BadRequest, HttpStatusCode.NotFound)) {
            calls.clear()
            val response = clients({ status(code) }).named("v-${code.value}").get("http://vendor/x")

            assertEquals(code, response.status)
            assertEquals(1, calls.size, "$code must not be retried")
        }
    }

    @Test
    fun `GET, PUT and DELETE that time out are retried`() = runBlocking {
        for ((index, send) in listOf<suspend (io.ktor.client.HttpClient) -> HttpResponse>(
            { it.get("http://vendor/x") }, { it.put("http://vendor/x") }, { it.delete("http://vendor/x") },
        ).withIndex()) {
            calls.clear()
            val client = clients(timeout(), { ok() }).named("v-$index")

            assertEquals(HttpStatusCode.OK, send(client).status)
            assertEquals(2, calls.size)
        }
    }

    @Test
    fun `a POST that times out is not retried when it has no idempotency key`() = runBlocking {
        val client = clients(timeout(), { ok() }).named("v")

        assertFailsWith<HttpRequestTimeoutException> { client.post("http://vendor/send-sms") { setBody("code") } }

        assertEquals(1, calls.size, "a slow network must never send the same SMS twice")
    }

    @Test
    fun `a POST that times out is retried with the same key when it carries one`() = runBlocking {
        val client = clients(timeout(), { ok() }).named("v")

        val response = client.post("http://vendor/transfer") {
            header(IDEMPOTENCY_KEY_HEADER, "key-1")
            setBody("pay")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf("key-1", "key-1"), calls.map { it.headers[IDEMPOTENCY_KEY_HEADER] })
    }

    @Test
    fun `attempts are capped at the configured number`() = runBlocking {
        val client = clients({ status(HttpStatusCode.ServiceUnavailable) }).named("v")

        val response = client.get("http://vendor/x")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals(3, calls.size, "three attempts in all, the first included")
    }

    @Test
    fun `backoff grows with each retry and stays inside its jitter band`() {
        for (retry in 1..4) {
            val step = minOf(5_000L, 200L * (1L shl (retry - 1)))
            repeat(50) { assertTrue(RetryPolicy.backoffMillis(retry, 200) in step / 2..step) }
        }
        assertTrue(RetryPolicy.backoffMillis(30, 200) <= 5_000, "capped")
    }

    // ---- the circuit breaker -----------------------------------------------------------------------------

    @Test
    fun `five failures in a row open the circuit and the next call fails without reaching the vendor`() = runBlocking {
        val client = clients({ status(HttpStatusCode.InternalServerError) }).named("v") { maxAttempts = 1 }

        repeat(5) { client.get("http://vendor/x") }
        assertEquals(5, calls.size)

        assertFailsWith<CircuitOpenException> { client.get("http://vendor/x") }
        assertEquals(5, calls.size, "an open circuit must not call the vendor")
    }

    @Test
    fun `every retried attempt counts toward the failures in a row`() = runBlocking {
        val client = clients({ status(HttpStatusCode.ServiceUnavailable) }).named("v") { circuitFailureThreshold = 3 }

        client.get("http://vendor/x") // 3 attempts, all failing

        assertFailsWith<CircuitOpenException> { client.get("http://vendor/x") }
        assertEquals(3, calls.size)
    }

    @Test
    fun `after the open time one probe goes through, and success closes the circuit`() = runBlocking {
        val client = clients({ status(HttpStatusCode.InternalServerError) }, { status(HttpStatusCode.InternalServerError) }, { ok() })
            .named("v") { maxAttempts = 1; circuitFailureThreshold = 2; circuitOpenFor = Duration.ofMillis(300) }
        repeat(2) { client.get("http://vendor/x") }
        assertFailsWith<CircuitOpenException> { client.get("http://vendor/x") }

        delay(400)
        val probe = client.get("http://vendor/x")

        assertEquals(HttpStatusCode.OK, probe.status)
        assertEquals(HttpStatusCode.OK, client.get("http://vendor/x").status, "closed again")
    }

    @Test
    fun `a failed probe opens the circuit again`() = runBlocking {
        val client = clients({ status(HttpStatusCode.InternalServerError) })
            .named("v") { maxAttempts = 1; circuitFailureThreshold = 1; circuitOpenFor = Duration.ofMillis(300) }
        client.get("http://vendor/x")
        delay(400)

        client.get("http://vendor/x") // the probe, fails
        assertFailsWith<CircuitOpenException> { client.get("http://vendor/x") }
    }

    @Test
    fun `a success in between resets the count of failures in a row`() = runBlocking {
        val client = clients({ status(HttpStatusCode.InternalServerError) }, { status(HttpStatusCode.InternalServerError) }, { ok() }, { status(HttpStatusCode.InternalServerError) })
            .named("v") { maxAttempts = 1; circuitFailureThreshold = 3 }

        repeat(4) { client.get("http://vendor/x") }

        assertEquals(4, calls.size)
        assertEquals(HttpStatusCode.InternalServerError, client.get("http://vendor/x").status, "still closed: only one failure since the success")
    }

    // ---- logging, metrics, configuration -------------------------------------------------------------------

    @Test
    fun `an attempt is logged and measured without its query, body or headers`() = runBlocking {
        val client = clients({ ok() }).named("africastalking")

        client.post("http://vendor/messaging?phone=%2B254712345678&code=482913") {
            operation("send_sms")
            header(HttpHeaders.Authorization, "Bearer secret-token")
            setBody("to=+254712345678&message=Your code is 482913")
        }

        val line = logs.list.single { it.message == "attempt" }
        val everything = line.formattedMessage + line.argumentArray.joinToString { it.toString() }
        assertTrue(everything.contains("url.path=/messaging"), everything)
        assertTrue(everything.contains("vendor=africastalking"), everything)
        for (secret in listOf("254712345678", "482913", "secret-token", "phone=")) {
            assertFalse(everything.contains(secret), "'$secret' must not be logged: $everything")
        }
        assertEquals(Metrics.INTEGRATION_DURATION, telemetry.records.single().first)
        assertEquals(mapOf("vendor" to "africastalking", "operation" to "send_sms", "outcome" to "ok"), telemetry.records.single().second)
    }

    @Test
    fun `a failed attempt logs only the exception class, never its message`() = runBlocking {
        val client = clients({ throw ConnectException("refused connecting to http://vendor/x?code=482913") }).named("v") { maxAttempts = 1 }

        assertFailsWith<ConnectException> { client.get("http://vendor/x") }

        val line = logs.list.single { it.message == "attempt failed" }
        assertFalse(line.argumentArray.joinToString { it.toString() }.contains("482913"))
        assertTrue(line.argumentArray.joinToString { it.toString() }.contains("error=ConnectException"))
        assertEquals("failure", telemetry.records.single().second["outcome"])
    }

    @Test
    fun `the base url is applied to relative paths, and a name always gives the same client`() = runBlocking {
        val clients = clients({ ok() })
        val client = clients.named("v") { baseUrl = "https://api.vendor.test/v2/" }

        client.get("messages")

        assertEquals("https://api.vendor.test/v2/messages", calls.single().url.toString())
        assertTrue(clients.named("v") === client)
    }
}
