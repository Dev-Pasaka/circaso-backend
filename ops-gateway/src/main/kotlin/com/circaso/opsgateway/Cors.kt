package com.circaso.opsgateway

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.response.header
import io.ktor.server.response.respond

/**
 * CORS for the portal only (decision 2026-11-08: the ops API has its own CORS). The portal is a static site on its own
 * host, so its calls are cross-origin and carry the Access cookie: an origin in `OPS_ALLOWED_ORIGINS` gets credentials
 * allowed for that exact origin, never `*`; any other origin gets no CORS headers, so the browser blocks the read. Kept
 * by hand (a few headers) rather than adding Ktor's CORS plugin as a new dependency.
 */
internal object Cors {
    private const val METHODS = "GET, POST, PATCH, OPTIONS"
    private const val HEADERS = "Content-Type, Idempotency-Key, Authorization"
    private const val EXPOSED = "Retry-After, Idempotent-Replayed, X-Request-Id, $ENVIRONMENT_HEADER"
    private const val MAX_AGE_SECONDS = "600"

    /** Adds the headers an allowed origin needs; true when the call was a preflight and has been answered. */
    suspend fun handle(call: ApplicationCall, allowed: Set<String>): Boolean {
        val origin = call.request.header(HttpHeaders.Origin)?.let(Origins::normalise)
        val isAllowed = origin != null && origin in allowed
        call.response.header(HttpHeaders.Vary, HttpHeaders.Origin)
        if (isAllowed) {
            call.response.header(HttpHeaders.AccessControlAllowOrigin, origin!!)
            call.response.header(HttpHeaders.AccessControlAllowCredentials, "true")
            call.response.header(HttpHeaders.AccessControlExposeHeaders, EXPOSED)
        }
        val isPreflight =
            call.request.httpMethod == HttpMethod.Options &&
                call.request.header(HttpHeaders.AccessControlRequestMethod) != null
        if (!isPreflight) return false
        if (isAllowed) {
            call.response.header(HttpHeaders.AccessControlAllowMethods, METHODS)
            call.response.header(HttpHeaders.AccessControlAllowHeaders, HEADERS)
            call.response.header(HttpHeaders.AccessControlMaxAge, MAX_AGE_SECONDS)
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respond(HttpStatusCode.Forbidden)
        }
        return true
    }
}
