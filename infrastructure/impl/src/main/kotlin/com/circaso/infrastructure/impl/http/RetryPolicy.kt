package com.circaso.infrastructure.impl.http

import com.circaso.infrastructure.api.IDEMPOTENCY_KEY_HEADER
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpMethod
import io.ktor.http.isWebsocket
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import kotlin.math.min
import kotlin.random.Random
import java.net.SocketTimeoutException as JdkSocketTimeoutException

/**
 * The retry table (Architecture → Infrastructure → The shared HTTP client), because retrying the wrong call
 * sends money or an OTP twice:
 *
 * | Situation | Retried? |
 * | --- | --- |
 * | Connection failed before the request reached the vendor | yes |
 * | `429` or `503` from the vendor | yes, after its `Retry-After` |
 * | `GET`, `PUT`, `DELETE` that time out | yes |
 * | `POST` with an idempotency key that times out | yes, with the same key |
 * | `POST` without one that times out | no: the caller decides |
 * | A WebSocket upgrade, whatever happened | no: the caller decides |
 */
object RetryPolicy {
    private val idempotentMethods =
        setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Put, HttpMethod.Delete, HttpMethod.Options)

    fun retryAfterException(request: HttpRequestBuilder, cause: Throwable): Boolean {
        if (request.isWebSocketUpgrade()) return false
        val chain = generateSequence(cause) { it.cause }.take(8).toList()
        if (chain.any(::failedBeforeSending)) return true
        if (chain.any(::timedOut)) return safeToRepeat(request)
        return false
    }

    fun retryAfterStatus(request: HttpRequest, status: Int): Boolean =
        !request.url.protocol.isWebsocket() && (status == 429 || status == 503)

    /** A request that may already have reached the vendor is repeated only when repeating is harmless. */
    private fun safeToRepeat(request: HttpRequestBuilder): Boolean =
        request.method in idempotentMethods || request.headers[IDEMPOTENCY_KEY_HEADER] != null

    private fun failedBeforeSending(e: Throwable) =
        e is ConnectTimeoutException || e is ConnectException || e is UnknownHostException ||
            e is UnresolvedAddressException || e is NoRouteToHostException

    private fun timedOut(e: Throwable) =
        e is HttpRequestTimeoutException || e is SocketTimeoutException || e is JdkSocketTimeoutException

    /** Exponential backoff with jitter: half the step plus a random half, capped. [retry] counts from 1. */
    fun backoffMillis(
        retry: Int,
        baseMillis: Long,
        capMillis: Long = 5_000,
    ): Long {
        val step = min(capMillis, baseMillis * (1L shl (retry - 1).coerceIn(0, 20)))
        return step / 2 + Random.nextLong(0, step / 2 + 1)
    }
}

/**
 * A WebSocket upgrade (a live voice socket) is never retried: the conversation it carried can't be replayed, so the
 * caller decides whether to open another.
 */
fun HttpRequestBuilder.isWebSocketUpgrade(): Boolean = url.protocol.isWebsocket()
