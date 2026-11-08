package com.circaso.integrations.impl.apns

import kotlinx.coroutines.future.await
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** One POST to APNs and what it answered. */
interface ApnsTransport {
    suspend fun post(
        path: String,
        headers: Map<String, String>,
        body: String,
    ): ApnsResponse
}

/** [apnsId] is APNs' own id for the push (`apns-id`), for support with Apple. */
data class ApnsResponse(val status: Int, val body: String, val apnsId: String?)

/**
 * APNs over HTTP/2 with the JDK's own client (decision 2026-11-08). APNs speaks only HTTP/2 and the repository's
 * Ktor engine (CIO) has none; Ktor's Java engine, which does, isn't on the classpath and adding it would be a new
 * dependency. So this one file is exempt from the `httpOnlyThroughKtor` architecture rule (see Rules.kt), and it
 * keeps the shared clients' promises itself: 5 s to connect, 15 s per request, no retries (a ring is a keyless
 * POST, and a late ring is worthless: the caller falls back to FCM instead), and nothing logged here. The OpenTelemetry
 * agent traces `java.net.http` as it does Ktor. One client, so one HTTP/2 connection to APNs is reused for every ring.
 */
class JdkApnsTransport(private val baseUrl: String) : ApnsTransport {
    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(CONNECT_SECONDS))
            .build()

    override suspend fun post(
        path: String,
        headers: Map<String, String>,
        body: String,
    ): ApnsResponse {
        val request =
            HttpRequest
                .newBuilder(URI.create(baseUrl.trimEnd('/') + path))
                .timeout(Duration.ofSeconds(REQUEST_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        return ApnsResponse(
            response.statusCode(),
            response.body(),
            response.headers().firstValue("apns-id").orElse(null),
        )
    }

    private companion object {
        const val CONNECT_SECONDS = 5L
        const val REQUEST_SECONDS = 15L
    }
}
