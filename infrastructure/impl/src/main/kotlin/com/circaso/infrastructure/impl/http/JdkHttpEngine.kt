package com.circaso.infrastructure.impl.http

import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineCapability
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.callContext
import io.ktor.client.engine.mergeHeaders
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * A vendor client's engine over the JDK's own `java.net.http.HttpClient`, which keeps its connections open between
 * calls (an idle one for 30 s, `jdk.httpclient.keepalive.timeout`) and opens another when calls overlap. Each call
 * then skips the TCP and TLS handshake, 60 to 100 ms to Circle (audit 2026-11-05, Q1). Ktor's CIO keeps a connection
 * only for a GET without its own timeouts, so every POST, and with the `HttpTimeout` plugin every call, paid it.
 *
 * Only what the vendor clients need: HTTP/1.1, no proxy, redirects left to Ktor, a body read whole before it is sent
 * (vendor bodies are small) and the answer streamed back. The request timeout is the `HttpTimeout` plugin's; the
 * connect timeout is this client's and fails as Ktor's [ConnectTimeoutException], so the retry table reads it the same.
 * WebSockets stay on CIO (see [VendorHttpClients]).
 */
@OptIn(InternalAPI::class)
internal class JdkHttpEngine(connectTimeout: Duration) : HttpClientEngineBase("circaso-jdk") {
    override val config: HttpClientEngineConfig = HttpClientEngineConfig()

    override val supportedCapabilities: Set<HttpClientEngineCapability<*>> = setOf(HttpTimeoutCapability)

    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val callContext = callContext()
        val requestTime = GMTDate()
        val request = HttpRequest.newBuilder(URI(data.url.toString())).method(data.method.value, publisher(data.body))
        mergeHeaders(data.headers, data.body) { name, value ->
            if (name.lowercase() !in RESTRICTED) request.header(name, value)
        }
        val response =
            try {
                client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofInputStream()).await()
            } catch (e: HttpConnectTimeoutException) {
                throw ConnectTimeoutException("Connect timeout has expired [url=${data.url.host}]", e)
            }
        val stream: InputStream = response.body()
        // Closing it when the call ends hands a fully read connection back to the pool (an unread one is dropped).
        callContext[Job]?.invokeOnCompletion { runCatching { stream.close() } }
        val headers =
            Headers.build {
                response.headers().map().forEach { (name, values) ->
                    if (!name.startsWith(":")) appendAll(name, values)
                }
            }
        return HttpResponseData(
            HttpStatusCode.fromValue(response.statusCode()),
            requestTime,
            headers,
            HttpProtocolVersion.HTTP_1_1,
            stream.toByteReadChannel(Dispatchers.IO),
            callContext,
        )
    }

    private suspend fun publisher(body: OutgoingContent): HttpRequest.BodyPublisher =
        when (body) {
            is OutgoingContent.NoContent -> {
                HttpRequest.BodyPublishers.noBody()
            }

            is OutgoingContent.ByteArrayContent -> {
                HttpRequest.BodyPublishers.ofByteArray(body.bytes())
            }

            is OutgoingContent.ReadChannelContent -> {
                HttpRequest.BodyPublishers.ofByteArray(body.readFrom().toByteArray())
            }

            is OutgoingContent.WriteChannelContent -> {
                val bytes =
                    coroutineScope {
                        val channel = ByteChannel()
                        launch {
                            body.writeTo(channel)
                            channel.flushAndClose()
                        }
                        channel.toByteArray()
                    }
                HttpRequest.BodyPublishers.ofByteArray(bytes)
            }

            is OutgoingContent.ContentWrapper -> {
                publisher(body.delegate())
            }

            is OutgoingContent.ProtocolUpgrade -> {
                throw UnsupportedOperationException("Protocol upgrades go through CIO")
            }
        }

    override fun close() {
        super.close()
        client.shutdownNow()
    }

    private companion object {
        /** Headers the JDK client sets itself and refuses from the caller. */
        val RESTRICTED = setOf("connection", "content-length", "expect", "host", "upgrade")
    }
}
