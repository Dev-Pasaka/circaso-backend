package com.circaso.infrastructure.impl.http

import com.circaso.infrastructure.api.HandshakeRefusedException
import com.circaso.infrastructure.api.HandshakeTimeoutException
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.TestApplication
import io.ktor.server.testing.TestApplicationBuilder
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import io.ktor.server.websocket.WebSockets as ServerWebSockets

/**
 * The `webSockets` flag on a vendor client, against a WebSocket server on Ktor's test host. The host is started by
 * hand under `runBlocking` rather than `testApplication`, whose `runTest` would also catch stray exceptions left behind
 * by other test classes in this JVM (Lettuce reconnecting after a Redis test) and fail here for them.
 */
class VendorWebSocketsTest {
    private val refusedUpgrades = AtomicInteger()
    private val plainCalls = AtomicInteger()

    /** Holds `/stuck` until the test is done with it, so no handler is left running once the host stops. */
    private val unstick = CompletableDeferred<Unit>()

    private fun TestApplicationBuilder.vendor() {
        application {
            install(ServerWebSockets)
            routing {
                webSocket("/echo") {
                    for (frame in incoming) {
                        if (frame is Frame.Text) send(Frame.Text("echo:" + frame.readText()))
                    }
                }
                get("/busy") {
                    refusedUpgrades.incrementAndGet()
                    call.respond(HttpStatusCode.ServiceUnavailable)
                }
                get("/plain") {
                    plainCalls.incrementAndGet()
                    call.respond(HttpStatusCode.ServiceUnavailable)
                }
                get("/stuck") {
                    unstick.await()
                    call.respond(HttpStatusCode.OK)
                }
            }
        }
    }

    private fun withVendor(test: suspend (VendorHttpClients) -> Unit) {
        val host = TestApplication { vendor() }
        runBlocking {
            host.start()
            try {
                test(VendorHttpClients(engine = { host.client.engine }, retryBaseMillis = 1))
            } finally {
                unstick.complete(Unit)
                delay(100)
                host.stop()
            }
        }
    }

    @Test
    fun `the flag installs the WebSockets plugin, and a client without it has none`() =
        withVendor { clients ->
            assertNotNull(clients.named("live") { webSockets = true }.pluginOrNull(WebSockets))
            assertNull(clients.named("plain").pluginOrNull(WebSockets))
        }

    @Test
    fun `an upgrade is never retried and never opens the circuit, while a plain call still is retried`() =
        withVendor { clients ->
            val client =
                clients.named("live") {
                    webSockets = true
                    circuitFailureThreshold = 3
                }

            repeat(3) {
                assertFailsWith<Exception> { client.webSocketSession("ws://localhost/busy") }
            }
            assertEquals(3, refusedUpgrades.get(), "one attempt per upgrade, despite the 503")

            client.get("http://localhost/plain")
            assertEquals(3, plainCalls.get(), "the circuit is still closed, and a GET keeps its retries")
        }

    /**
     * Ktor's test host fails a refused upgrade inside its own engine, so this one runs the real engine against a
     * socket that answers like AI Gateway refusing Aura: a `400` with a JSON body, which Ktor would otherwise try to
     * read as the socket and fail with a serialization error.
     */
    @Test
    fun `an upgrade answered with an error status fails as refused, with that status`() {
        val refusing = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val server =
            thread(isDaemon = true) {
                refusing.accept().use { socket ->
                    val request = socket.getInputStream().bufferedReader()
                    while (request.readLine()?.isNotEmpty() == true) continue
                    val body = """{"name":"AiGatewayError","internalCode":2029}"""
                    socket.getOutputStream().write(
                        (
                            "HTTP/1.1 400 Bad Request\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                        ).toByteArray(),
                    )
                }
            }
        val clients = VendorHttpClients()
        try {
            val client = clients.named("live") { webSockets = true }

            val refused =
                assertFailsWith<HandshakeRefusedException> {
                    runBlocking { client.webSocketSession("ws://127.0.0.1:${refusing.localPort}/run") }
                }

            assertEquals(400, refused.status)
            assertEquals("live", refused.vendor)
        } finally {
            clients.close()
            refusing.close()
            server.join(1_000)
        }
    }

    @Test
    fun `an open socket outlives the request timeout`() =
        withVendor { clients ->
            val client =
                clients.named("live") {
                    webSockets = true
                    requestTimeout = Duration.ofMillis(200)
                }

            val socket = client.webSocketSession("ws://localhost/echo")
            delay(600)
            socket.send(Frame.Text("still there"))

            assertEquals("echo:still there", (socket.incoming.receive() as Frame.Text).readText())
            socket.close()
        }

    @Test
    fun `a socket that doesn't open in time fails with a handshake timeout`() =
        withVendor { clients ->
            val client =
                clients.named("live") {
                    webSockets = true
                    handshakeTimeout = Duration.ofMillis(300)
                }

            assertFailsWith<HandshakeTimeoutException> { client.webSocketSession("ws://localhost/stuck") }
        }
}
