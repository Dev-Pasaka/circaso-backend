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
 *
 * The Redis steps go out together, not one after another: the token's deny-list lookup, the rate-limit counter and,
 * for a state change, the idempotency claim are written to the connection back to back and share one round trip
 * ([Admissions]). Their answers are then read in the order above, so the caller gets what the steps run in turn would
 * have answered; a step that ran ahead of an earlier refusal is undone (the count of a request whose session turned
 * out revoked, the claim of a request refused for its rate).
 */
fun Application.installGatewayEdge(deps: GatewayDeps) {
    val admissions = Admissions(RateLimiter(deps.redis.scope("gw")), Idempotency(deps.redis.scope("gw")))

    // A plain interceptor rather than a plugin hook: `finish()` is what stops the route from running after a refusal.
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.apiPath()
        if (!path.isApi() || path.isWebhook()) return@intercept

        val admitted =
            when (val outcome = admit(call, path, deps, admissions)) {
                is Admission.Refused -> {
                    respondRefusal(call, outcome.error)
                    return@intercept finish()
                }

                is Admission.Counted -> {
                    outcome
                }
            }
        admitted.limited?.let { error ->
            admitted.release()
            call.respondError(error)
            return@intercept finish()
        }

        val claim = admitted.claim ?: return@intercept
        val key = claim.key
        if (key == null) {
            call.respondError(SharedError.IdempotencyKeyMissing)
            return@intercept finish()
        }
        if (claim.claimed) {
            call.attributes.put(
                ResponseRecorderKey,
                ResponseRecorder { status, json -> admissions.idempotency.finish(key, status, json) },
            )
            return@intercept
        }
        when (val state = admissions.idempotency.state(key)) {
            is IdempotencyState.Done -> {
                call.attributes.put(EnvelopeSent, true)
                call.response.header("Idempotent-Replayed", "true")
                call.respondText(state.json, ContentType.Application.Json, HttpStatusCode.fromValue(state.status))
            }

            else -> {
                call.respondError(SharedError.InProgress)
            }
        }
        finish()
    }
}

private suspend fun respondRefusal(call: ApplicationCall, error: DomainError) {
    // The one refusal whose status isn't its kind's: 426 tells the app to show Force update.
    if (error === SharedError.ClientOutdated) {
        call.respondDefault(
            DefaultResponse<Unit>(HttpStatusCode.UpgradeRequired.value, false, error.message, error.code),
        )
    } else {
        call.respondError(error)
    }
}

/**
 * Version, authentication and scope rules, with the rate limit and the idempotency claim sent alongside the token's
 * deny-list lookup. [Admission.Refused] for a refusal of the first three; otherwise what the counter and the claim
 * found, for the caller to read in order.
 */
private suspend fun admit(
    call: ApplicationCall,
    path: String,
    deps: GatewayDeps,
    admissions: Admissions,
): Admission {
    ClientVersion.parseHeader(call.request.header(ClientVersion.HEADER))?.let { (platform, version) ->
        val minimum = deps.config.minClientVersions[platform]
        if (minimum != null && version < minimum) return Admission.Refused(SharedError.ClientOutdated)
    }
    val method = call.request.httpMethod.value

    val token =
        call.request
            .header(HttpHeaders.Authorization)
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)
            ?.trim()
    if (token == null) {
        return scopeRefusal(null, path, method)?.let(Admission::Refused) ?: admissions.count(call, path, method, null)
    }

    // Set by the work sent alongside, so a failing deny-list lookup can still take back the idempotency claim.
    var counted: Admission.Counted? = null
    val (verified, alongside) =
        try {
            deps.verifier.verifyAlongside(token) { p ->
                val principal = CallPrincipal(p.userId, p.deviceId, p.sessionId.value, p.scope)
                scopeRefusal(principal, path, method)?.let(Admission::Refused)
                    ?: admissions.count(call, path, method, principal).also { counted = it }
            }
        } catch (e: Throwable) {
            counted?.abandon()
            throw e
        }
    return when (verified) {
        is Resource.Error -> {
            // A revoked session never got past authentication: its request neither counts nor holds its key.
            (alongside as? Admission.Counted)?.undo()
            Admission.Refused(verified.error)
        }

        is Resource.Success -> {
            val p = verified.data
            call.attributes.put(CallPrincipalKey, CallPrincipal(p.userId, p.deviceId, p.sessionId.value, p.scope))
            alongside ?: Admission.Refused(SharedError.Internal)
        }
    }
}

/** The scope rules: what a caller with [principal] (or none) may not reach. */
private fun scopeRefusal(
    principal: CallPrincipal?,
    path: String,
    method: String,
): DomainError? {
    val agentRoute = path.isAgent()
    return when {
        agentRoute && principal == null -> SharedError.TokenMissing

        agentRoute && principal!!.scope != Scope.AGENT -> SharedError.ScopeInsufficient

        // An agent token reaches nothing but GET under /v1/agent/ (and the JWKS, which carries no data).
        principal?.scope == Scope.AGENT && !agentRoute && !path.isPublicDoc() -> SharedError.ScopeInsufficient

        principal?.scope == Scope.AGENT && method != "GET" -> SharedError.ScopeInsufficient

        else -> null
    }
}
