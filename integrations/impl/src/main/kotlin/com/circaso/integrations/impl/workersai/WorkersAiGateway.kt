package com.circaso.integrations.impl.workersai

import com.circaso.infrastructure.api.operation
import com.circaso.integrations.api.IntegrationError
import com.circaso.shared.Resource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.retry
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.Duration

/** A successful answer from Workers AI: the body as Cloudflare sent it, and its content type. */
class GatewayReply(val bytes: ByteArray, val contentType: String?) {
    /**
     * The body as [T], unwrapped from Cloudflare's `{"result": …, "success": true}` envelope when it has one (the
     * model routes `run/<model>`; the OpenAI-compatible routes answer bare). Null when it is neither.
     */
    internal fun <T : Any> decode(serializer: KSerializer<T>): T? {
        val text = String(bytes)
        val wrapped =
            runCatching {
                WorkersAiJson.decodeFromString(CloudflareEnvelope.serializer(serializer), text).result
            }.getOrNull()
        return wrapped ?: runCatching { WorkersAiJson.decodeFromString(serializer, text) }.getOrNull()
    }
}

/** Cloudflare's envelope around a model route's answer. */
@Serializable
private class CloudflareEnvelope<T>(val result: T? = null)

/** Cloudflare's errors on a refusal; only their numeric codes are read. */
@Serializable
private class CloudflareErrors(val errors: List<CloudflareError> = emptyList())

@Serializable
private class CloudflareError(val code: Long? = null)

/**
 * The one way the Workers AI integrations reach Cloudflare: every model call goes through AI Gateway on the AI REST
 * API (`https://api.cloudflare.com/client/v4/accounts/<account>/ai/`, the base URL of [http]), named by the
 * `cf-aig-gateway-id` header. Each request also sends `cf-aig-collect-log: false`, so the gateway keeps no log of
 * prompts, transcripts or audio even if its dashboard setting is switched back on.
 *
 * Failures, in our language: our token refused (`401`, `403`) or a model id Cloudflare doesn't know (`404`) is our
 * mistake and logged as an error; `429`, `5xx`, a timeout or an open circuit means Workers AI is busy or down; all of
 * those are `Unavailable`. Any other `4xx` is Cloudflare refusing what we sent (`Rejected`). Only the status, the
 * operation, Cloudflare's numeric error codes and an exception's class are logged: never a body.
 */
class WorkersAiGateway(
    private val http: HttpClient,
    private val token: String,
    private val gateway: String,
    /** How long a streamed answer may take in all, as the client's 60 s request timeout bounds a plain call. */
    private val streamDeadline: Duration = Duration.ofSeconds(60),
) {
    private val log = LoggerFactory.getLogger(WorkersAiGateway::class.java)

    /**
     * POSTs [body] (JSON, encoded from the caller's request class) to [path] (relative to the AI REST API, like
     * `v1/chat/completions`). With [retried] false the shared retry rules are off for this call: a model turn can
     * already run to the 60 s timeout, and a user shouldn't wait for several of them (Architecture → Infrastructure,
     * the shared HTTP client).
     */
    suspend fun post(
        path: String,
        operation: String,
        body: String,
        retried: Boolean = true,
    ): Resource<GatewayReply> {
        val (status, reply) =
            try {
                val response =
                    http.post(path) {
                        model(operation, body)
                        if (!retried) retry { noRetry() }
                    }
                response.status.value to response.reply()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("workers-ai unreachable: operation={} error={}", operation, e::class.simpleName)
                return Resource.error(IntegrationError.Unavailable(VENDOR), e)
            }
        return if (status in 200..299) Resource.success(reply) else refusal(status, reply, operation)
    }

    /**
     * POSTs [body] to [path] and hands the answer back line by line as Cloudflare sends it (server-sent events), for a
     * model reply streamed with `stream: true`. Never retried, like a model turn by [post]. A refusal (any status
     * but `2xx`) is the [Resource.Error], mapped as [post] maps it; once the answer has started, a cut connection or
     * the [streamDeadline] shows as [GatewayStream.isBroken].
     *
     * The lines are read by a child of the caller's coroutine, so cancelling the caller stops the read; a caller that
     * stops early closes the stream.
     */
    suspend fun stream(
        path: String,
        operation: String,
        body: String,
    ): Resource<GatewayStream> {
        val opened = CompletableDeferred<Resource<Unit>>()
        val lines = Channel<String>(STREAM_BUFFER_LINES)
        val reader =
            CoroutineScope(currentCoroutineContext()).launch {
                try {
                    withTimeout(streamDeadline.toMillis()) { read(path, operation, body, opened, lines) }
                    lines.close()
                } catch (e: TimeoutCancellationException) {
                    cutOff(operation, e, opened, lines)
                } catch (e: CancellationException) {
                    lines.close()
                    opened.complete(Resource.error(IntegrationError.Unavailable(VENDOR), e))
                    throw e
                } catch (e: Exception) {
                    cutOff(operation, e, opened, lines)
                }
            }
        return when (val start = opened.await()) {
            is Resource.Success -> Resource.success(GatewayStream(lines, reader))
            is Resource.Error -> start
        }
    }

    private suspend fun read(
        path: String,
        operation: String,
        body: String,
        opened: CompletableDeferred<Resource<Unit>>,
        lines: Channel<String>,
    ) = http
        .preparePost(path) {
            model(operation, body)
            retry { noRetry() }
            header(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
        }.execute { response ->
            val status = response.status.value
            if (status !in 200..299) {
                opened.complete(refusal(status, response.reply(), operation))
                return@execute
            }
            opened.complete(Resource.success(Unit))
            val answer = response.bodyAsChannel()
            while (true) lines.send(answer.readUTF8Line(MAX_LINE_CHARS) ?: break)
        }

    /** Before the answer started, Workers AI was unreachable; after, the answer was cut off. Logged by class only. */
    private fun cutOff(
        operation: String,
        error: Throwable,
        opened: CompletableDeferred<Resource<Unit>>,
        lines: Channel<String>,
    ) {
        if (opened.complete(Resource.error(IntegrationError.Unavailable(VENDOR), error))) {
            log.warn("workers-ai unreachable: operation={} error={}", operation, error::class.simpleName)
            lines.close()
            return
        }
        log.warn("workers-ai stream cut off: operation={} error={}", operation, error::class.simpleName)
        lines.close(error)
    }

    /** The headers every model call carries: our token, the gateway, its log switched off, and the JSON [body]. */
    private fun HttpRequestBuilder.model(operation: String, body: String) {
        operation(operation)
        header(HttpHeaders.Authorization, "Bearer $token")
        header(GATEWAY_HEADER, gateway)
        header(COLLECT_LOG_HEADER, "false")
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpResponse.reply() = GatewayReply(bodyAsBytes(), headers[HttpHeaders.ContentType])

    private fun refusal(
        status: Int,
        reply: GatewayReply,
        operation: String,
    ): Resource.Error {
        val codes = errorCodes(reply)
        return when {
            status == 401 || status == 403 || status == 404 -> {
                log.error(
                    "workers-ai refused our token, gateway or model id: status={} operation={} codes={}",
                    status,
                    operation,
                    codes,
                )
                Resource.Error(IntegrationError.Unavailable(VENDOR))
            }

            status == 429 || status >= 500 -> {
                log.warn("workers-ai unavailable: status={} operation={} codes={}", status, operation, codes)
                Resource.Error(IntegrationError.Unavailable(VENDOR))
            }

            else -> {
                log.warn("workers-ai refused the request: status={} operation={} codes={}", status, operation, codes)
                Resource.Error(IntegrationError.Rejected("Workers AI refused the request (status $status)."))
            }
        }
    }

    /** Cloudflare's numeric error codes (`10000` is a token without Workers AI permission); never the messages. */
    private fun errorCodes(reply: GatewayReply): List<Long> =
        runCatching { WorkersAiJson.decodeFromString(CloudflareErrors.serializer(), String(reply.bytes)) }
            .getOrNull()
            ?.errors
            ?.mapNotNull { it.code }
            .orEmpty()

    companion object {
        const val VENDOR = "Workers AI"
        const val GATEWAY_HEADER = "cf-aig-gateway-id"
        const val COLLECT_LOG_HEADER = "cf-aig-collect-log"

        /** Lines read ahead of the caller; the reader waits when the caller is this far behind. */
        private const val STREAM_BUFFER_LINES = 64

        /** The longest line read; a chunk of a model answer is a few hundred characters. */
        private const val MAX_LINE_CHARS = 1_000_000

        /** The AI REST API of one account; AI Gateway is chosen per request by [GATEWAY_HEADER]. */
        fun baseUrl(accountId: String) = "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/"
    }
}
