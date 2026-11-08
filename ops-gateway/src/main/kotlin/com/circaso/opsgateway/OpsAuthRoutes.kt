package com.circaso.opsgateway

import com.circaso.shared.Resource
import com.circaso.web.NoBody
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.respondError
import com.circaso.web.toDefaultResponse
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.receive
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.net.URI

internal const val AUTH_CONFIG_PATH = "/v1/ops/auth/config"
internal const val AUTH_HANDOFF_PATH = "/v1/ops/auth/handoff"
internal const val DEV_LOGIN_PATH = "/v1/ops/auth/dev-login"
private const val TAG = "Ops · Sign in"

/** What the portal's sign-in page needs to know before anyone is signed in. */
@Serializable
data class AuthConfigResponse(val environment: String, val accessEnabled: Boolean, val devLoginEnabled: Boolean)

@Serializable
data class DevLoginRequest(val email: String = "", val secret: String = "")

@Serializable
data class DevLoginStaffDto(
    val id: String,
    val email: String,
    val role: String,
    val active: Boolean,
    val fromConfig: Boolean,
)

@Serializable
data class DevLoginResponse(val token: String, val expiresAt: String, val staff: DevLoginStaffDto)

/**
 * The sign-in routes, the only ones that take no sign-in:
 * - `GET auth/config`: whether Access and the dev login are on, for the sign-in page.
 * - `GET auth/handoff?return=`: the portal sends the browser here (a top-level visit, not a fetch) when a call answers
 *   `ops.sign_in_required`. Cloudflare Access signs the person in on this host on the way, setting its cookie here,
 *   and this sends them back to the portal; only to an origin in `OPS_ALLOWED_ORIGINS`, so it can't be an open redirect.
 * - `POST auth/dev-login`: the sandbox dev login (404 `ops.dev_login_disabled` unless `OPS_DEV_LOGIN_SECRET` is set).
 */
internal fun Route.opsAuthRoutes(deps: OpsGatewayDeps) {
    route("/ops/auth") {
        get("config") {
            val config =
                AuthConfigResponse(
                    environment =
                        deps.config.environment.name
                            .lowercase(),
                    accessEnabled = deps.access != null,
                    devLoginEnabled = deps.devLogin != null,
                )
            call.respondDefault(Resource.success(config).toDefaultResponse { it })
        }.docs<NoBody, AuthConfigResponse>("OPS · How staff sign in here", TAG, notes = "No sign-in needed.")

        get("handoff") {
            val target = call.request.queryParameters["return"]?.let { returnTarget(it, deps.config.allowedOrigins) }
            if (target == null) {
                call.respondError(OpsGatewayError.ReturnUrlInvalid)
            } else {
                call.respondRedirect(target, permanent = false)
            }
        }.docs<NoBody, NoBody>(
            "OPS · Back to the portal after Cloudflare Access",
            TAG,
            listOf("ops.return_url_invalid"),
            "A browser visit, not a fetch. Redirects (302) to `return` when its origin is one of OPS_ALLOWED_ORIGINS.",
        )

        post("dev-login") {
            val devLogin = deps.devLogin
            if (devLogin == null) {
                call.respondError(OpsGatewayError.DevLoginDisabled)
                return@post
            }
            val request = call.receive<DevLoginRequest>()
            val staff = if (devLogin.secretMatches(request.secret)) deps.directory.signIn(request.email) else null
            if (staff == null) {
                call.respondError(OpsGatewayError.DevLoginRefused)
                return@post
            }
            val issued = devLogin.issue(staff.id, staff.email)
            deps.directory.recordDevLogin(staff, call.callId)
            val response =
                DevLoginResponse(
                    token = issued.token,
                    expiresAt = issued.expiresAt.toString(),
                    staff =
                        DevLoginStaffDto(
                            staff.id.toString(),
                            staff.email,
                            staff.role.name,
                            staff.active,
                            staff.fromConfig,
                        ),
                )
            call.respondDefault(Resource.success(response).toDefaultResponse { it })
        }.docs<DevLoginRequest, DevLoginResponse>(
            "OPS · Dev login (sandbox only)",
            TAG,
            listOf("ops.dev_login_disabled", "ops.dev_login_refused", "request.rate_limited"),
            notes =
                "Off unless OPS_DEV_LOGIN_SECRET is set, and the server refuses to start with it in production. " +
                    "Five tries a minute per address. Audited as `staff.dev_login`.",
        )
    }
}

/** [raw] when it is an absolute http(s) URL on an allowed origin, else null. */
internal fun returnTarget(raw: String, allowed: Set<String>): String? {
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    if (!uri.isAbsolute || uri.userInfo != null) return null
    val origin = Origins.normalise(raw) ?: return null
    return raw.takeIf { origin in allowed }
}
