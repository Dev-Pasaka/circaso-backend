package com.circaso.integrations.impl.workersai

import com.circaso.infrastructure.api.HandshakeRefusedException
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
 * as a bearer header (Cloudflare's Workers AI WebSocket examples). Deepgram's options travel in the query string,
 * `mip_opt_out=true` among them.
 *
 * AI Gateway (decision 2026-10-27, from the real smoke test): Nova-3's socket goes through the gateway, named by the
 * `cf-aig-gateway-id` header with its log off (`cf-aig-collect-log: false`), as the HTTP routes do. AI Gateway refuses
 * Aura's socket (`400`, "Unsupported websocket provider/model"), so Aura connects to Workers AI directly, with no
 * `cf-aig-*` header: [open] with `throughGateway = false`.
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
        throughGateway: Boolean = true,
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
                    if (throughGateway) {
                        header(WorkersAiGateway.GATEWAY_HEADER, gateway)
                        header(WorkersAiGateway.COLLECT_LOG_HEADER, "false")
                    }
                }
            Resource.success(session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HandshakeRefusedException) {
            // the status is on the attempt log line; Workers AI or the gateway said no, nothing more to tell
            log.warn("workers-ai live socket refused: operation={}", operation)
            Resource.error(IntegrationError.Unavailable(WorkersAiGateway.VENDOR))
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
