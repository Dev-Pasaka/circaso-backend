package com.circaso.gateway

import com.circaso.identity.api.TokenVerifier
import com.circaso.infrastructure.api.RedisScopes
import com.circaso.shared.DomainError
import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.SharedError
import com.circaso.web.CallPrincipal
import com.circaso.web.CallPrincipalKey
import com.circaso.web.DefaultResponse
import com.circaso.web.EnvelopeSent
import com.circaso.web.ResponseRecorder
import com.circaso.web.ResponseRecorderKey
import com.circaso.web.principalOrNull
import com.circaso.web.respondDefault
import com.circaso.web.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText

/** What the edge needs from the rest of the backend. */
class GatewayDeps(val config: GatewayConfig, val verifier: TokenVerifier, val redis: RedisScopes)

private val API = "/v1"

private fun ApplicationCall.apiPath(): String = request.path()

// The API reader is an edge page too: it takes the same token check, so staging can put it behind the ops scope.
private fun String.isApi() = this == API || startsWith("$API/") || this == "/docs" || startsWith("/docs/")

private fun String.isAgent() = startsWith("$API/agent/") || this == "$API/agent"

private fun String.isWebhook() = startsWith("$API/hooks/")

private fun String.isPublicDoc() = startsWith("$API/.well-known/")

fun ApplicationCall.clientIp(): String =
    request.header("CF-Connecting-IP")?.trim()?.takeIf { it.isNotEmpty() } ?: request.local.remoteHost

/**
 * The gateway's per-request steps, in the order the architecture lists them: client version, authentication,
 * scope rules (the agent token can only `GET` under `/v1/agent/`), rate limits, then idempotency. The first step
 * that refuses answers with the envelope and the route never runs. Request ids, JSON and StatusPages are
 * installed by `shared-web`; the request log is [RequestLog].
 */
fun Application.installGatewayEdge(deps: GatewayDeps) {
    val limiter = RateLimiter(deps.redis.scope("gw"))
    val idempotency = Idempotency(deps.redis.scope("gw"))

    // A plain interceptor rather than a plugin hook: `finish()` is what stops the route from running after a refusal.
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.apiPath()
        if (!path.isApi()) return@intercept

        refusal(call, path, deps)?.let { error ->
            // The one refusal whose status isn't its kind's: 426 tells the app to show Force update.
            if (error === SharedError.ClientOutdated) {
                call.respondDefault(
                    DefaultResponse<Unit>(HttpStatusCode.UpgradeRequired.value, false, error.message, error.code),
                )
            } else {
                call.respondError(error)
            }
            return@intercept finish()
        }
        if (path.isWebhook()) return@intercept

        val principal = call.principalOrNull()
        val method = call.request.httpMethod.value

        RateLimitTable.limitFor(method, path, principal != null)?.let { limit ->
            val subject =
                when (limit.by) {
                    LimitBy.USER -> principal?.userId?.toString() ?: call.clientIp()
                    LimitBy.IP -> call.clientIp()
                }
            limiter.check(limit, subject)?.let { error ->
                call.respondError(error)
                return@intercept finish()
            }
        }

        if (method in Idempotency.mutating) {
            val clientKey =
                call.request
                    .header(Idempotency.HEADER)
                    ?.trim()
                    .orEmpty()
            if (clientKey.isEmpty()) {
                call.respondError(SharedError.IdempotencyKeyMissing)
                return@intercept finish()
            }
            val key = Idempotency.redisKey(principal?.userId?.toString() ?: call.clientIp(), method, path, clientKey)
            if (idempotency.begin(key)) {
                call.attributes.put(
                    ResponseRecorderKey,
                    ResponseRecorder {
                        status,
                        json,
                        ->
                        idempotency.finish(key, status, json)
                    },
                )
            } else {
                when (val state = idempotency.state(key)) {
                    is IdempotencyState.Done -> {
                        call.attributes.put(EnvelopeSent, true)
                        call.response.header("Idempotent-Replayed", "true")
                        call.respondText(
                            state.json,
                            ContentType.Application.Json,
                            HttpStatusCode.fromValue(state.status),
                        )
                    }

                    else -> {
                        call.respondError(SharedError.InProgress)
                    }
                }
                finish()
            }
        }
    }
}

/** Version, authentication and scope rules; returns the error to answer with, or null to carry on. */
private suspend fun refusal(
    call: ApplicationCall,
    path: String,
    deps: GatewayDeps,
): DomainError? {
    if (path.isWebhook()) return null

    ClientVersion.parseHeader(call.request.header(ClientVersion.HEADER))?.let { (platform, version) ->
        val minimum = deps.config.minClientVersions[platform]
        if (minimum != null && version < minimum) return SharedError.ClientOutdated
    }

    val token =
        call.request
            .header(HttpHeaders.Authorization)
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)
            ?.trim()
    if (token != null) {
        when (val result = deps.verifier.verify(token)) {
            is Resource.Error -> {
                return result.error
            }

            is Resource.Success -> {
                val p = result.data
                call.attributes.put(CallPrincipalKey, CallPrincipal(p.userId, p.deviceId, p.sessionId.value, p.scope))
            }
        }
    }

    val principal = call.principalOrNull()
    val agentRoute = path.isAgent()
    return when {
        agentRoute && principal == null -> SharedError.TokenMissing

        agentRoute && principal!!.scope != Scope.AGENT -> SharedError.ScopeInsufficient

        // An agent token reaches nothing but GET under /v1/agent/ (and the JWKS, which carries no data).
        principal?.scope == Scope.AGENT && !agentRoute && !path.isPublicDoc() -> SharedError.ScopeInsufficient

        principal?.scope == Scope.AGENT && call.request.httpMethod.value != "GET" -> SharedError.ScopeInsufficient

        else -> null
    }
}
