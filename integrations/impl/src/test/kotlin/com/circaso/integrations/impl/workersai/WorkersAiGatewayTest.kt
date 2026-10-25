package com.circaso.integrations.impl.workersai

import com.circaso.infrastructure.api.CircuitOpenException
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.impl.FakeHttpClients
import com.circaso.integrations.impl.FakeHttpClients.Companion.json
import com.circaso.integrations.impl.text
import com.circaso.shared.Resource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class WorkersAiGatewayTest {
    private var status = 200
    private var reply = """{"ok":true}"""
    private var failure: Exception? = null

    private val clients =
        FakeHttpClients { _ ->
            failure?.let { throw it }
            json(reply, status)
        }
    private val gateway = WorkersAiKit.gateway(clients)
    private val body = buildJsonObject { put("model", "@cf/x/y") }

    private suspend fun post() = gateway.post("v1/chat/completions", "chat_completion", body)

    @Test
    fun `every call goes to the account's AI REST API through the named gateway, with its logs off`() =
        runBlocking<Unit> {
            assertIs<Resource.Success<*>>(post())

            val request = clients.last
            assertEquals(listOf("workers-ai"), clients.names)
            assertEquals(
                "https://api.cloudflare.com/client/v4/accounts/acc123/ai/v1/chat/completions",
                "${request.url}",
            )
            assertEquals("POST", request.method.value)
            assertEquals("Bearer cf-token", request.headers[HttpHeaders.Authorization])
            assertEquals("asha", request.headers["cf-aig-gateway-id"])
            assertEquals("false", request.headers["cf-aig-collect-log"], "the gateway must not keep prompts")
            assertEquals("""{"model":"@cf/x/y"}""", request.text())
        }

    @Test
    fun `our token, gateway or model refused is Unavailable, not the caller's fault`() =
        runBlocking<Unit> {
            for (code in listOf(401, 403, 404)) {
                status = code
                reply = """{"success":false,"errors":[{"code":10000,"message":"Authentication error"}]}"""
                assertIs<IntegrationError.Unavailable>((post() as Resource.Error).error, "status $code")
            }
        }

    @Test
    fun `a rate limit or a Workers AI outage is Unavailable`() =
        runBlocking<Unit> {
            for (code in listOf(429, 500, 502, 503)) {
                status = code
                assertIs<IntegrationError.Unavailable>((post() as Resource.Error).error, "status $code")
            }
        }

    @Test
    fun `a request Workers AI refuses is Rejected`() =
        runBlocking<Unit> {
            for (code in listOf(400, 413, 422)) {
                status = code
                assertIs<IntegrationError.Rejected>((post() as Resource.Error).error, "status $code")
            }
        }

    @Test
    fun `an unreachable Workers AI or an open circuit is Unavailable`() =
        runBlocking<Unit> {
            failure = IOException("connection reset")
            assertIs<IntegrationError.Unavailable>((post() as Resource.Error).error)
            failure = CircuitOpenException("workers-ai")
            assertIs<IntegrationError.Unavailable>((post() as Resource.Error).error)
        }

    @Test
    fun `a request that times out is Unavailable`() =
        runBlocking<Unit> {
            val slow =
                HttpClient(
                    MockEngine { _ ->
                        delay(5_000)
                        error("never answers")
                    },
                ) {
                    install(HttpTimeout) { requestTimeoutMillis = 50 }
                    defaultRequest { url(WorkersAiGateway.baseUrl("acc123")) }
                }
            val result = WorkersAiGateway(slow, "cf-token", "asha").post("v1/chat/completions", "chat_completion", body)
            assertIs<IntegrationError.Unavailable>((result as Resource.Error).error)
        }

    @Test
    fun `a cancelled call stays cancelled`() {
        failure = CancellationException("turn abandoned")
        assertFailsWith<CancellationException> { runBlocking { post() } }
    }

    @Test
    fun `Cloudflare's result envelope is unwrapped`() {
        val wrapped = GatewayReply("""{"result":{"text":"hi"},"success":true}""".toByteArray(), "application/json")
        assertEquals("hi", wrapped.json()?.text("text"))
        val bare = GatewayReply("""{"text":"hi"}""".toByteArray(), "application/json")
        assertEquals("hi", bare.json()?.text("text"))
    }
}
