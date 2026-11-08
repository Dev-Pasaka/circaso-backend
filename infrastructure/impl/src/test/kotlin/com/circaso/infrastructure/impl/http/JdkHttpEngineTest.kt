package com.circaso.infrastructure.impl.http

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The vendor clients' default engine ([JdkHttpEngine]) against a real socket: a tiny HTTP/1.1 server that keeps its connections
 * alive and counts how many it accepted, so connection reuse is measured, not assumed.
 */
class JdkHttpEngineTest {
    private val accepted = AtomicInteger()
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val base = "http://127.0.0.1:${server.localPort}"

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                accepted.incrementAndGet()
                thread(isDaemon = true) { serve(socket) }
            }
        }
    }

    /** Answers `{}` to each request on the socket until the client closes it; `/slow` waits 300 ms first. */
    private fun serve(socket: Socket) =
        socket.use {
            val input = BufferedInputStream(it.getInputStream())
            val output = it.getOutputStream()
            while (true) {
                val head = readHead(input) ?: return
                val length =
                    head
                        .lineSequence()
                        .firstOrNull { line -> line.startsWith("content-length:", ignoreCase = true) }
                        ?.substringAfter(':')
                        ?.trim()
                        ?.toInt() ?: 0
                input.readNBytes(length)
                if (head.startsWith("GET /slow") || head.startsWith("POST /slow")) Thread.sleep(SLOW_MILLIS)
                val closing = head.startsWith("GET /close")
                val extra = if (closing) "Connection: close\r\n" else ""
                output.write(
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n${extra}Content-Length: 2\r\n\r\n{}"
                        .toByteArray(),
                )
                output.flush()
                if (closing) return
            }
        }

    private fun readHead(input: InputStream): String? {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val byte = input.read()
            if (byte < 0) return null
            head.append(byte.toChar())
        }
        return head.toString()
    }

    @AfterTest
    fun stop() = server.close()

    private fun clients() = VendorHttpClients(retryBaseMillis = 1)

    @Test
    fun `calls one after another share one connection, GETs and POSTs alike`() =
        runBlocking {
            clients().use { clients ->
                val client = clients.named("vendor") { baseUrl = base }
                repeat(10) { assertEquals("{}", client.get("/answer").bodyAsText()) }
                repeat(5) { assertEquals("{}", client.post("/answer") { setBody("{\"a\":1}") }.bodyAsText()) }

                assertEquals(1, accepted.get(), "15 calls in a row, one TCP connection")
            }
        }

    @Test
    fun `calls at once each get a connection of their own and run side by side`() =
        runBlocking {
            clients().use { clients ->
                val client = clients.named("vendor") { baseUrl = base }
                client.get("/answer") // the client's own start-up, out of the timing
                val started = System.nanoTime()
                coroutineScope { List(3) { async { client.get("/slow").bodyAsText() } }.awaitAll() }
                val tookMillis = (System.nanoTime() - started) / 1_000_000

                assertEquals(3, accepted.get(), "three calls at once, three connections (the first one reused)")
                assertTrue(tookMillis < 2 * SLOW_MILLIS, "side by side, not one after another (took $tookMillis ms)")

                // And the three are kept: three more at once open nothing new.
                coroutineScope { List(3) { async { client.get("/slow").bodyAsText() } }.awaitAll() }
                assertEquals(3, accepted.get())
            }
        }

    @Test
    fun `a connection the vendor closed is dropped from the pool, and the next call opens another`() =
        runBlocking {
            clients().use { clients ->
                val client = clients.named("vendor") { baseUrl = base }
                client.get("/answer")
                client.get("/close")
                delay(200)

                assertEquals("{}", client.post("/answer") { setBody("{}") }.bodyAsText(), "a POST, never retried")
                assertEquals(2, accepted.get())
            }
        }

    @Test
    fun `the answer streams, and a request body, headers and status come through`() =
        runBlocking {
            clients().use { clients ->
                val client = clients.named("vendor") { baseUrl = base }
                val response = client.post("/answer") { setBody("{\"a\":1}") }
                assertEquals(200, response.status.value)
                assertEquals("application/json", response.headers["Content-Type"])
                assertEquals("{}", response.bodyAsText())
            }
        }

    private companion object {
        const val SLOW_MILLIS = 300L
    }
}
