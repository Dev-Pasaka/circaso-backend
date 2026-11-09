package com.circaso.infrastructure.impl.http

import com.circaso.infrastructure.api.CircuitOpenException
import com.circaso.infrastructure.api.HandshakeRefusedException
import com.circaso.infrastructure.api.HandshakeTimeoutException
import com.circaso.infrastructure.api.HttpClients
import com.circaso.infrastructure.api.HttpOperation
import com.circaso.infrastructure.api.VendorHttpConfig
import com.circaso.infrastructure.impl.database.requireOutsideTransaction
import com.circaso.infrastructure.impl.logging.kv
import com.circaso.infrastructure.impl.logging.millisSince
import com.circaso.observability.api.Metrics
import com.circaso.observability.api.NoopTelemetry
import com.circaso.observability.api.Telemetry
import com.circaso.shared.CircasoJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodedPath
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

private val log = LoggerFactory.getLogger("com.circaso.infrastructure.impl.http.Vendor")

/**
 * One named Ktor client per vendor: timeouts, the retry table, a circuit breaker, a metric and a log line per
 * attempt. Bodies, query strings and headers are never logged, so a phone number, a code or a token can't reach a log.
 */
class VendorHttpClients(
    private val telemetry: Telemetry = NoopTelemetry,
    /** The engine for one vendor's client; by default [defaultEngine]. */
    private val engine: (VendorHttpConfig) -> HttpClientEngine = ::defaultEngine,
    private val clock: Clock = Clock.systemUTC(),
    private val retryBaseMillis: Long = 200,
) : HttpClients,
    AutoCloseable {
    private val clients = ConcurrentHashMap<String, HttpClient>()

    override fun named(name: String, configure: VendorHttpConfig.() -> Unit): HttpClient =
        clients.computeIfAbsent(name) { build(name, VendorHttpConfig().apply(configure)) }

    override fun close() = clients.values.forEach { it.close() }

    private fun build(vendor: String, config: VendorHttpConfig): HttpClient {
        val breaker = CircuitBreaker(vendor, config.circuitFailureThreshold, config.circuitOpenFor, clock)
        telemetry.gauge(
            Metrics.INTEGRATION_CIRCUIT_OPEN,
            mapOf("vendor" to vendor),
        ) { if (breaker.isOpen) 1.0 else 0.0 }

        return HttpClient(engine(config)) {
            expectSuccess = false // vendor errors are answers to translate, not exceptions
            install(outsideTransactions(vendor))
            install(ContentNegotiation) { json(CircasoJson) }
            install(HttpTimeout) {
                connectTimeoutMillis = config.connectTimeout.toMillis()
                requestTimeoutMillis = config.requestTimeout.toMillis()
            }
            defaultRequest { config.baseUrl?.let { url(it) } }
            if (config.webSockets) install(WebSockets) { pingIntervalMillis = config.pingInterval.toMillis() }

            // Installed from the outside in: retries wrap the breaker, which wraps the logging of each attempt,
            // so every attempt is logged, counted by the breaker, and a failing vendor is cut off between retries.
            install(HttpRequestRetry) {
                maxRetries = config.maxAttempts - 1
                retryOnExceptionIf { request, cause -> RetryPolicy.retryAfterException(request, cause) }
                retryIf { request, response -> RetryPolicy.retryAfterStatus(request, response.status.value) }
                delayMillis(
                    respectRetryAfterHeader = true,
                ) { retry -> RetryPolicy.backoffMillis(retry, retryBaseMillis) }
            }
            install(CircuitBreakerPlugin) { this.breaker = breaker }
            if (config.webSockets) install(handshakeTimeout(vendor, config.handshakeTimeout))
            install(attemptLogging(vendor, breaker))
        }
    }

    /** A vendor call inside a database transaction would hold its pooled connection for the whole wait: refused. */
    private fun outsideTransactions(vendor: String) =
        createClientPlugin("VendorOutsideTransactions-$vendor") {
            onRequest { _, _ -> requireOutsideTransaction("HTTP to $vendor") }
        }

    /**
     * Bounds the opening of a socket only. `HttpTimeout`'s request timeout leaves WebSockets alone, so without this a
     * vendor that accepted the connection but never answered the upgrade would hold the caller forever. Once the
     * `101` is in, the socket lives as long as the conversation.
     *
     * An upgrade answered with anything but `101` is [HandshakeRefusedException]: left to Ktor, the vendor's error
     * body would be read as the socket and fail as a serialization error that hides what happened. Its status is in
     * the attempt log line, written before this sees the answer.
     */
    private fun handshakeTimeout(vendor: String, timeout: Duration) =
        createClientPlugin("VendorHandshake-$vendor") {
            on(Send) { request ->
                if (!request.isWebSocketUpgrade()) return@on proceed(request)
                val call =
                    withTimeoutOrNull(timeout.toMillis()) { proceed(request) }
                        ?: throw HandshakeTimeoutException(vendor)
                val status = call.response.status
                if (status == HttpStatusCode.SwitchingProtocols) return@on call
                // nobody reads the refusal's body: let go of it and the connection before failing
                runCatching { call.response.cancel() }
                throw HandshakeRefusedException(vendor, status.value)
            }
        }

    private fun attemptLogging(vendor: String, breaker: CircuitBreaker) =
        createClientPlugin("VendorLogging-$vendor") {
            on(Send) { request ->
                val start = System.nanoTime()
                val operation = request.attributes.getOrNull(HttpOperation) ?: "request"
                val where =
                    arrayOf(
                        kv("kind", "http"),
                        kv("vendor", vendor),
                        kv("http.method", request.method.value),
                        kv("url.path", request.url.encodedPath), // never the query string
                    )
                try {
                    val call = proceed(request)
                    val status = call.response.status.value
                    val outcome =
                        when {
                            status >= 500 -> "server_error"
                            status >= 400 -> "client_error"
                            else -> "ok"
                        }
                    record(vendor, operation, outcome, start)
                    val line =
                        arrayOf(
                            *where,
                            kv("http.status", status),
                            kv("durationMs", 0L.millisSince(start)),
                            kv("circuit", if (breaker.isOpen) "open" else "closed"),
                        )
                    if (status >= 500 || status == 429) log.warn("attempt", *line) else log.info("attempt", *line)
                    call
                } catch (e: CancellationException) {
                    throw e
                } catch (e: CircuitOpenException) {
                    record(vendor, operation, "circuit_open", start)
                    throw e
                } catch (e: Exception) {
                    record(vendor, operation, "failure", start)
                    // the exception class only: a message can carry a URL or a body fragment
                    log.warn(
                        "attempt failed",
                        *where,
                        kv("durationMs", 0L.millisSince(start)),
                        kv("error", e::class.simpleName),
                    )
                    throw e
                }
            }
        }

    private fun record(
        vendor: String,
        operation: String,
        outcome: String,
        startNanos: Long,
    ) = telemetry.record(
        Metrics.INTEGRATION_DURATION,
        (System.nanoTime() - startNanos) / 1e9,
        mapOf("vendor" to vendor, "operation" to operation, "outcome" to outcome),
    )
}

/**
 * The JDK's client, which keeps connections open between calls ([JdkHttpEngine]); CIO for a client that opens
 * WebSockets, which the JDK's client can't upgrade to through Ktor, or that asks for it ([VendorHttpConfig.jdkEngine]).
 */
internal fun defaultEngine(config: VendorHttpConfig): HttpClientEngine =
    if (config.webSockets || !config.jdkEngine) CIO.create() else JdkHttpEngine(config.connectTimeout)
