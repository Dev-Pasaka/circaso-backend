package com.circaso.integrations.impl.workersai

import com.circaso.infrastructure.api.operation
import com.circaso.integrations.api.IntegrationError
import com.circaso.shared.Resource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.retry
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/** A successful answer from Workers AI: the body as Cloudflare sent it, and its content type. */
class GatewayReply(val bytes: ByteArray, val contentType: String?) {
    /** The body as JSON, unwrapped from Cloudflare's `{"result": …, "success": true}` envelope when it has one. */
    fun json(): JsonObject? =
        runCatching {
            val root = Json.parseToJsonElement(String(bytes)).jsonObject
            (root["result"] as? JsonObject) ?: root
        }.getOrNull()
}

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
class WorkersAiGateway(private val http: HttpClient, private val token: String, private val gateway: String) {
    private val log = LoggerFactory.getLogger(WorkersAiGateway::class.java)

    /**
     * POSTs [body] to [path] (relative to the AI REST API, like `v1/chat/completions`). With [retried] false the
     * shared retry rules are off for this call: a model turn can already run to the 60 s timeout, and a user
     * shouldn't wait for several of them (Architecture → Infrastructure, the shared HTTP client).
     */
    suspend fun post(
        path: String,
        operation: String,
        body: JsonObject,
        retried: Boolean = true,
    ): Resource<GatewayReply> {
        val (status, reply) =
            try {
                val response =
                    http.post(path) {
                        operation(operation)
                        if (!retried) retry { noRetry() }
                        header(HttpHeaders.Authorization, "Bearer $token")
                        header(GATEWAY_HEADER, gateway)
                        header(COLLECT_LOG_HEADER, "false")
                        contentType(ContentType.Application.Json)
                        setBody(body.toString())
                    }
                response.status.value to
                    GatewayReply(response.bodyAsBytes(), response.headers[HttpHeaders.ContentType])
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("workers-ai unreachable: operation={} error={}", operation, e::class.simpleName)
                return Resource.error(IntegrationError.Unavailable(VENDOR), e)
            }
        return answer(status, reply, operation)
    }

    private fun answer(
        status: Int,
        reply: GatewayReply,
        operation: String,
    ): Resource<GatewayReply> {
        if (status in 200..299) return Resource.success(reply)

        val codes = errorCodes(reply)
        return when {
            status == 401 || status == 403 || status == 404 -> {
                log.error(
                    "workers-ai refused our token, gateway or model id: status={} operation={} codes={}",
                    status,
                    operation,
                    codes,
                )
                Resource.error(IntegrationError.Unavailable(VENDOR))
            }

            status == 429 || status >= 500 -> {
                log.warn("workers-ai unavailable: status={} operation={} codes={}", status, operation, codes)
                Resource.error(IntegrationError.Unavailable(VENDOR))
            }

            else -> {
                log.warn("workers-ai refused the request: status={} operation={} codes={}", status, operation, codes)
                Resource.error(IntegrationError.Rejected("Workers AI refused the request (status $status)."))
            }
        }
    }

    /** Cloudflare's numeric error codes (`10000` is a token without Workers AI permission); never the messages. */
    private fun errorCodes(reply: GatewayReply): List<String> =
        runCatching {
            Json
                .parseToJsonElement(String(reply.bytes))
                .jsonObject["errors"]
                ?.jsonArray
                ?.mapNotNull { (it as? JsonObject)?.get("code")?.codeText() }
                .orEmpty()
        }.getOrDefault(emptyList())

    private fun JsonElement.codeText(): String? = runCatching { jsonPrimitive.content }.getOrNull()

    companion object {
        const val VENDOR = "Workers AI"
        const val GATEWAY_HEADER = "cf-aig-gateway-id"
        const val COLLECT_LOG_HEADER = "cf-aig-collect-log"

        /** The AI REST API of one account; AI Gateway is chosen per request by [GATEWAY_HEADER]. */
        fun baseUrl(accountId: String) = "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/"
    }
}
