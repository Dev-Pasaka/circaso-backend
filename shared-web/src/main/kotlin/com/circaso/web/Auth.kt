package com.circaso.web

import com.circaso.shared.DeviceId
import com.circaso.shared.DomainException
import com.circaso.shared.Scope
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.server.routing.intercept
import io.ktor.util.AttributeKey
import java.util.UUID

/** Who the gateway authenticated for this call; set once per request, read by routes and controllers. */
data class CallPrincipal(
    val userId: UserId,
    val deviceId: DeviceId,
    val sessionId: UUID,
    val scope: Scope,
)

val CallPrincipalKey = AttributeKey<CallPrincipal>("circaso.principal")

fun ApplicationCall.principalOrNull(): CallPrincipal? = attributes.getOrNull(CallPrincipalKey)

/** The caller. Only valid inside `authenticate { }`, which refuses a call without one. */
fun ApplicationCall.principal(): CallPrincipal = principalOrNull() ?: throw DomainException(SharedError.TokenMissing)

fun ApplicationCall.userId(): UserId = principal().userId

/**
 * A route group that needs a token with one of [scopes]. The gateway has already verified the token and set
 * the principal; this only checks the scope the route declares. Routes without it take no token (nonce, JWKS).
 */
// Route-scoped plugins can't stop a route after answering; `intercept` plus `finish()` can.
@Suppress("DEPRECATION")
fun Route.authenticate(vararg scopes: Scope, build: Route.() -> Unit): Route {
    require(scopes.isNotEmpty()) { "authenticate needs at least one scope" }
    val allowed = scopes.toSet()
    val node =
        createChild(
            object : RouteSelector() {
                override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int) =
                    RouteSelectorEvaluation.Transparent

                override fun toString() = "(authenticate ${scopes.joinToString { it.wire }})"
            },
        )
    node.intercept(ApplicationCallPipeline.Plugins) {
        val principal = call.principalOrNull()
        val refusal =
            when {
                principal == null -> SharedError.TokenMissing
                principal.scope !in allowed -> SharedError.ScopeInsufficient
                else -> null
            }
        if (refusal != null) {
            call.respondError(refusal)
            finish()
        }
    }
    node.build()
    return node
}

/**
 * What a module hands the gateway: its routes, relative to `/v1`, installed with its controllers
 * (`route.profileController(service)`). The gateway mounts every contributor under `/v1`.
 */
fun interface RouteContributor {
    fun install(route: Route)
}

/**
 * What a module hands the gateway for the few public pages outside `/v1` (share link pages and the well-known app
 * association files, decision 2026-11-06), mounted at the root. They are for browsers and link previews, not the app:
 * the gateway's version check, tokens, scopes, rate limits and idempotency don't apply, so a page brings its own
 * guards (its own rate limit, `noindex`, a strict content security policy) and answers with the page itself.
 */
fun interface PageContributor {
    fun install(route: Route)
}
