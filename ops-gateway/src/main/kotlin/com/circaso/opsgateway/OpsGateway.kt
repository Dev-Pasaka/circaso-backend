package com.circaso.opsgateway

import com.circaso.gateway.Idempotency
import com.circaso.gateway.IdempotencyState
import com.circaso.gateway.Limit
import com.circaso.gateway.LimitBy
import com.circaso.gateway.RateLimiter
import com.circaso.gateway.clientIp
import com.circaso.gateway.installRequestDecodingErrors
import com.circaso.infrastructure.api.RedisScopes
import com.circaso.ops.api.StaffDirectory
import com.circaso.opsgateway.auth.AccessCheck
import com.circaso.opsgateway.auth.AccessVerifier
import com.circaso.opsgateway.auth.DevLogin
import com.circaso.shared.DomainError
import com.circaso.shared.SharedError
import com.circaso.web.EnvelopeSent
import com.circaso.web.OpsRouteContributor
import com.circaso.web.ResponseRecorder
import com.circaso.web.ResponseRecorderKey
import com.circaso.web.StaffAccessError
import com.circaso.web.StaffPrincipal
import com.circaso.web.StaffPrincipalKey
import com.circaso.web.StaffSignIn
import com.circaso.web.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import java.time.Duration

private val log = LoggerFactory.getLogger("com.circaso.opsgateway.OpsGateway")

/** What the staff edge needs. [access] is null until Cloudflare Access is configured; [devLogin] null unless enabled. */
class OpsGatewayDeps(
    val config: OpsGatewayConfig,
    val access: AccessVerifier?,
    val devLogin: DevLogin?,
    val directory: StaffDirectory,
    val redis: RedisScopes,
)

/** The routes that need no sign-in: what the sign-in page asks, the Access hand-off and the dev login. */
internal val PUBLIC_PATHS = setOf(AUTH_CONFIG_PATH, AUTH_HANDOFF_PATH, DEV_LOGIN_PATH)

private val MINUTE: Duration = Duration.ofMinutes(1)

/** Cloudflare Access puts the application token in this header on every request it lets through. */
const val ACCESS_HEADER = "Cf-Access-Jwt-Assertion"

/** …and in this cookie in the browser, which the portal's cross-origin calls carry with `credentials: "include"`. */
const val ACCESS_COOKIE = "CF_Authorization"

/**
 * The staff edge (decision 2026-11-08), in order on every call: security headers, CORS (answering the portal's
 * preflights), then for `/v1`: sign-in (Cloudflare Access, else the sandbox dev login's bearer token), the staff list
 * (an active staff member for the verified email, else 403 `ops.not_staff`), the rate limit, and an `Idempotency-Key`
 * on every change. Then `/v1` with the sign-in routes and every [OpsRouteContributor]; each route checks the role it
 * needs itself (`staffOnly`). Nothing of the app gateway's routing, scopes or socket is installed here.
 */
fun Application.installOpsGateway(deps: OpsGatewayDeps, contributors: List<OpsRouteContributor>) {
    val limiter = RateLimiter(deps.redis.scope("ogw"))
    val idempotency = Idempotency(deps.redis.scope("ogw"))
    val staffLimit = Limit("ops-staff", deps.config.staffPerMinute, MINUTE, LimitBy.USER)
    val publicLimit = Limit("ops-public", deps.config.publicPerMinute, MINUTE, LimitBy.IP)
    val devLoginLimit = Limit("ops-dev-login", DEV_LOGINS_PER_MINUTE, MINUTE, LimitBy.IP)

    installRequestDecodingErrors()
    install(OpsRequestLog)

    intercept(ApplicationCallPipeline.Plugins) {
        secureHeaders(call)
        if (Cors.handle(call, deps.config.allowedOrigins)) return@intercept finish()
        val path = call.request.path()
        if (!path.startsWith("/v1/")) return@intercept

        if (path in PUBLIC_PATHS) {
            val limit = if (path == DEV_LOGIN_PATH) devLoginLimit else publicLimit
            limiter.check(limit, call.clientIp())?.let {
                call.respondError(it)
                return@intercept finish()
            }
            return@intercept
        }

        val principal =
            when (val signedIn = signIn(call, deps)) {
                is SignIn.Refused -> {
                    call.respondError(signedIn.error)
                    return@intercept finish()
                }

                is SignIn.Staff -> {
                    signedIn.principal
                }
            }
        call.attributes.put(StaffPrincipalKey, principal)

        limiter.check(staffLimit, principal.staffId.toString())?.let {
            call.respondError(it)
            return@intercept finish()
        }
        if (call.request.httpMethod.value in Idempotency.mutating && !claimIdempotency(call, principal, idempotency)) {
            return@intercept finish()
        }
    }

    routing {
        route("/v1") {
            opsAuthRoutes(deps)
            contributors.forEach { it.install(this) }
            opsSpecRoute(this@installOpsGateway, deps.config)
        }
    }
}

private sealed interface SignIn {
    data class Staff(val principal: StaffPrincipal) : SignIn

    data class Refused(val error: DomainError) : SignIn
}

/**
 * Access first: when Access is on, its token is what counts (Cloudflare adds the header; the browser sends the
 * cookie). The dev login's bearer token only where the dev login is on. The staff list is read on every request, so a
 * member switched off in the portal is refused from their next call.
 */
private suspend fun signIn(call: ApplicationCall, deps: OpsGatewayDeps): SignIn {
    val access = deps.access
    val accessToken = call.request.header(ACCESS_HEADER) ?: call.request.cookies[ACCESS_COOKIE]
    if (access != null && accessToken != null) {
        return when (val check = access.verify(accessToken)) {
            is AccessCheck.Verified -> {
                staffFor(check.email, StaffSignIn.ACCESS, deps)
            }

            is AccessCheck.Invalid -> {
                log.debug("Access token refused: check={}", check.why)
                SignIn.Refused(OpsGatewayError.AccessTokenInvalid)
            }

            AccessCheck.Unavailable -> {
                SignIn.Refused(OpsGatewayError.AccessUnavailable)
            }
        }
    }
    val devLogin = deps.devLogin
    val bearer =
        call.request
            .header(HttpHeaders.Authorization)
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(BEARER.length)
            ?.trim()
    if (devLogin != null && bearer != null) {
        val claims = devLogin.verify(bearer) ?: return SignIn.Refused(OpsGatewayError.AccessTokenInvalid)
        val signedIn = staffFor(claims.email, StaffSignIn.DEV, deps)
        // The token names who it was issued to; a staff row made again for the same email is someone new.
        if (signedIn is SignIn.Staff && signedIn.principal.staffId.toString() != claims.sub) {
            return SignIn.Refused(OpsGatewayError.AccessTokenInvalid)
        }
        return signedIn
    }
    return SignIn.Refused(StaffAccessError.SignInRequired)
}

private suspend fun staffFor(
    email: String,
    method: StaffSignIn,
    deps: OpsGatewayDeps,
): SignIn {
    val staff = deps.directory.signIn(email) ?: return SignIn.Refused(OpsGatewayError.NotStaff)
    return SignIn.Staff(StaffPrincipal(staff.id, staff.email, staff.role, method))
}

/** Same rules as the app gateway's: keyed by staff member, method, path and key; the first answer replayed 24 h. */
private suspend fun claimIdempotency(
    call: ApplicationCall,
    principal: StaffPrincipal,
    idempotency: Idempotency,
): Boolean {
    val clientKey =
        call.request
            .header(Idempotency.HEADER)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    if (clientKey == null) {
        call.respondError(SharedError.IdempotencyKeyMissing)
        return false
    }
    val key =
        Idempotency.redisKey(
            "ops:${principal.staffId}",
            call.request.httpMethod.value,
            call.request.path(),
            clientKey,
        )
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
        return true
    }
    when (val state = idempotency.state(key)) {
        is IdempotencyState.Done -> {
            call.attributes.put(EnvelopeSent, true)
            call.response.header("Idempotent-Replayed", "true")
            call.respondText(state.json, ContentType.Application.Json, HttpStatusCode.fromValue(state.status))
        }

        else -> {
            call.respondError(SharedError.InProgress)
        }
    }
    return false
}

/** The API never belongs in a frame, in a cache or in a referrer. */
private fun secureHeaders(call: ApplicationCall) {
    call.response.header(HttpHeaders.CacheControl, "no-store")
    call.response.header("X-Content-Type-Options", "nosniff")
    call.response.header("X-Frame-Options", "DENY")
    call.response.header("Referrer-Policy", "no-referrer")
}

private const val BEARER = "Bearer "
private const val DEV_LOGINS_PER_MINUTE = 5L
