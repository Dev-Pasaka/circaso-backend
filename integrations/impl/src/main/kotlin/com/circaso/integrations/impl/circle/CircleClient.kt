package com.circaso.integrations.impl.circle

import com.circaso.infrastructure.api.CircuitOpenException
import com.circaso.infrastructure.api.IDEMPOTENCY_KEY_HEADER
import com.circaso.infrastructure.api.operation
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import org.slf4j.LoggerFactory

/**
 * Circle's REST API over the shared `HttpClients.named("circle")` client, with the API key on every call. Each POST or
 * PUT carries its idempotency key twice: in the body ([CircleWrite]), where Circle reads it, and in the
 * `Idempotency-Key` header, which is what lets the shared client retry it after a timeout (Circle answers a repeated
 * key with the first result). Bodies and answers are never logged: they carry addresses, amounts and user tokens.
 */
internal class CircleClient(private val http: HttpClient, private val apiKey: String) {
    private val log = LoggerFactory.getLogger(CircleClient::class.java)

    suspend inline fun <reified R> get(
        path: String,
        operation: String,
        userToken: String? = null,
    ): CircleReply<R> = exchange(HttpMethod.Get, path, operation, userToken, body = null, serializer<R>())

    suspend inline fun <reified B : CircleWrite, reified R> write(
        method: HttpMethod,
        path: String,
        operation: String,
        body: B,
        userToken: String? = null,
    ): CircleReply<R> {
        val written = WrittenBody(body.idempotencyKey, circleJson.encodeToString(serializer<B>(), body))
        return exchange(method, path, operation, userToken, written, serializer<R>())
    }

    /** A body already encoded, with the key it carries. */
    class WrittenBody(val idempotencyKey: String, val text: String)

    suspend fun <R> exchange(
        method: HttpMethod,
        path: String,
        operation: String,
        userToken: String?,
        body: WrittenBody?,
        answer: KSerializer<R>,
    ): CircleReply<R> =
        try {
            val response =
                http.request(path) {
                    this.method = method
                    operation(operation)
                    authorize(userToken)
                    body?.let {
                        header(IDEMPOTENCY_KEY_HEADER, it.idempotencyKey)
                        contentType(ContentType.Application.Json)
                        setBody(it.text)
                    }
                }
            CircleReply.of(response.status.value, response.bodyAsText(), answer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CircuitOpenException) {
            CircleReply.Unreachable(e)
        } catch (e: Exception) {
            log.warn("circle unreachable: operation={} error={}", operation, e::class.simpleName)
            CircleReply.Unreachable(e)
        }

    private fun HttpRequestBuilder.authorize(userToken: String?) {
        header(HttpHeaders.Authorization, "Bearer $apiKey")
        userToken?.let { header(USER_TOKEN_HEADER, it) }
    }

    private companion object {
        const val USER_TOKEN_HEADER = "X-User-Token"
    }
}

/** What Circle answered: its `data`, its error code, or nothing at all. */
internal sealed class CircleReply<out T> {
    data class Ok<T>(val data: T) : CircleReply<T>()

    /** [code] is Circle's own (`155106`), or null when the answer had none or couldn't be read. */
    data class Failed(val status: Int, val code: Int?) : CircleReply<Nothing>()

    class Unreachable(val cause: Exception) : CircleReply<Nothing>()

    companion object {
        /** A success whose body can't be read is a failure with no code, which is `Unavailable` for the caller. */
        fun <T> of(
            status: Int,
            text: String,
            answer: KSerializer<T>,
        ): CircleReply<T> {
            if (status in 200..299) {
                val data =
                    runCatching { circleJson.decodeFromString(CircleEnvelope.serializer(answer), text).data }
                        .getOrNull()
                return if (data != null) Ok(data) else Failed(status, null)
            }
            val code = runCatching { circleJson.decodeFromString<CircleErrorBody>(text).code }.getOrNull()
            return Failed(status, code)
        }
    }
}
