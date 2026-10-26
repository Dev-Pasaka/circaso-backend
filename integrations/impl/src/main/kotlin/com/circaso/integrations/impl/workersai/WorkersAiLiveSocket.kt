package com.circaso.integrations.impl.workersai

import com.circaso.infrastructure.api.operation
import com.circaso.integrations.api.IntegrationError
import com.circaso.shared.Resource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * Opens Workers AI's live sockets (Deepgram Nova-3 and Aura on Workers AI) for [WorkersAiLiveTranscription] and
 * [WorkersAiLiveSpeech], through the `workers-ai-live` client (`webSockets = true`, so the socket is never retried and
 * only its opening is timed).
 *
 * The socket is `wss://api.cloudflare.com/client/v4/accounts/<account>/ai/run/<model>?<options>` with the API token
 * as a bearer header (Cloudflare's Workers AI WebSocket examples). As on the HTTP routes, AI Gateway is named by the
 * `cf-aig-gateway-id` header and its log is switched off with `cf-aig-collect-log: false`, so no transcript or audio
 * is kept by the gateway. Deepgram's options travel in the query string, `mip_opt_out=true` among them.
 *
 * TODO(decision: Cloudflare documents the gateway header for the AI REST API's HTTP routes, and documents live
 *  sockets through AI Gateway only at gateway.ai.cloudflare.com/v1/<account>/<gateway>/workers-ai?model=…; the
 *  smoke test confirms the header is honoured on a live socket at api.cloudflare.com)
 */
class WorkersAiLiveSocket(
    private val http: HttpClient,
    private val token: String,
    private val gateway: String,
    /** `wss://api.cloudflare.com/client/v4/accounts/<account>/ai/`; tests point it at a local server. */
    private val socketBase: String,
) {
    private val log = LoggerFactory.getLogger(WorkersAiLiveSocket::class.java)

    suspend fun open(
        model: String,
        operation: String,
        options: Map<String, String>,
    ): Resource<DefaultClientWebSocketSession> {
        val url =
            URLBuilder(socketBase)
                .apply {
                    appendPathSegments("run")
                    appendPathSegments(model.split('/'))
                    options.forEach { (name, value) -> parameters.append(name, value) }
                }.buildString()
        return try {
            val session =
                http.webSocketSession(url) {
                    operation(operation)
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header(WorkersAiGateway.GATEWAY_HEADER, gateway)
                    header(WorkersAiGateway.COLLECT_LOG_HEADER, "false")
                }
            Resource.success(session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the class only: a handshake error's message carries the URL
            log.warn("workers-ai live socket didn't open: operation={} error={}", operation, e::class.simpleName)
            Resource.error(IntegrationError.Unavailable(WorkersAiGateway.VENDOR), e)
        }
    }

    companion object {
        /** The live socket base of one account; [WorkersAiGateway.baseUrl] with `wss`. */
        fun socketBase(accountId: String) = "wss://api.cloudflare.com/client/v4/accounts/$accountId/ai/"
    }
}
