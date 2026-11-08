package com.circaso.web

import com.circaso.shared.DomainError
import com.circaso.shared.DomainException
import com.circaso.shared.ErrorKind
import com.circaso.shared.Scope
import com.circaso.shared.StaffRole
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

/** How a staff member proved who they are: Cloudflare Access in every real environment, or the sandbox dev login. */
enum class StaffSignIn { ACCESS, DEV }

/**
 * The staff member behind an ops gateway call (decision 2026-11-08). Set once per request by the ops gateway after it
 * verified the Cloudflare Access token (or the dev login's) and found an active staff member for its email. The scope is
 * always [Scope.OPS]: no route issues an ops token to a phone, and the app gateway never sets this.
 */
data class StaffPrincipal(
    val staffId: UUID,
    val email: String,
    val role: StaffRole,
    val signIn: StaffSignIn,
) {
    val scope: Scope get() = Scope.OPS

    // The email is personal data: never printed whole by accident (a log line, an exception).
    override fun toString(): String = "StaffPrincipal($staffId, $role, $signIn)"
}

val StaffPrincipalKey = AttributeKey<StaffPrincipal>("circaso.staff")

fun ApplicationCall.staffOrNull(): StaffPrincipal? = attributes.getOrNull(StaffPrincipalKey)

/** The staff member. Only valid inside [staffOnly], which refuses a call without one. */
fun ApplicationCall.staff(): StaffPrincipal = staffOrNull() ?: throw DomainException(StaffAccessError.SignInRequired)

/** The two refusals every staff route can answer, whatever module serves it. */
sealed class StaffAccessError(override val code: String, override val message: String, override val kind: ErrorKind) :
    DomainError {
    data object SignInRequired : StaffAccessError(
        "ops.sign_in_required",
        "Sign in to the Circaso admin to continue.",
        ErrorKind.UNAUTHORIZED,
    )

    data object RoleForbidden : StaffAccessError(
        "ops.role_forbidden",
        "Your role can't do that.",
        ErrorKind.FORBIDDEN,
    )
}

/**
 * A route group only staff with one of [roles] may reach. The ops gateway has already set the principal; this checks the
 * role the route declares, so the server, not the portal's menu, is the gate. Mirrors [authenticate].
 */
// Route-scoped plugins can't stop a route after answering; `intercept` plus `finish()` can.
@Suppress("DEPRECATION")
fun Route.staffOnly(roles: Set<StaffRole>, build: Route.() -> Unit): Route {
    require(roles.isNotEmpty()) { "staffOnly needs at least one role" }
    val node =
        createChild(
            object : RouteSelector() {
                override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int) =
                    RouteSelectorEvaluation.Transparent

                override fun toString() = "(staff ${roles.joinToString { it.name }})"
            },
        )
    node.intercept(ApplicationCallPipeline.Plugins) {
        val principal = call.staffOrNull()
        val refusal =
            when {
                principal == null -> StaffAccessError.SignInRequired
                principal.role !in roles -> StaffAccessError.RoleForbidden
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
 * What a module hands the ops gateway: its staff routes, relative to `/v1`. Only the ops gateway collects these; the app
 * gateway mounts [RouteContributor]s only, so a staff route can never be reached from the app's host (decision
 * 2026-11-08: "separate endpoint, separate management, don't mix them together").
 */
fun interface OpsRouteContributor {
    fun install(route: Route)
}
