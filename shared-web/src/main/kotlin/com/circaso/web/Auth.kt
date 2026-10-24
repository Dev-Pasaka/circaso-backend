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
