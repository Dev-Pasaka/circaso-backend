package com.circaso.web

import com.circaso.shared.CircasoJson
import com.circaso.shared.DomainError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.callid.callId
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.util.AttributeKey
import kotlinx.serialization.serializer

/** Set when a route already answered with the envelope, so StatusPages doesn't replace its body. */
val EnvelopeSent = AttributeKey<Boolean>("circaso.envelope.sent")

/**
 * Set by the gateway on calls that carry an `Idempotency-Key`: it receives the status and the JSON body of the
 * envelope being sent, so the first response can be stored and replayed.
 */
val ResponseRecorderKey = AttributeKey<ResponseRecorder>("circaso.response.recorder")

fun interface ResponseRecorder {
    suspend fun record(status: Int, json: String)
}

/**
 * Sends the envelope with the request id filled in. `Retry-After` is set when the error carries one.
 * Routes answer through here, never with a bare `call.respond`, so the app always gets the envelope.
 */
suspend inline fun <reified R> ApplicationCall.respondDefault(body: DefaultResponse<R>) {
    attributes.put(EnvelopeSent, true)
    body.details?.retryAfterSeconds?.let { response.header(HttpHeaders.RetryAfter, it.toString()) }
    val sent = body.copy(requestId = callId)
    attributes
        .getOrNull(
            ResponseRecorderKey,
        )?.record(sent.httpStatusCode, CircasoJson.encodeToString(serializer<DefaultResponse<R>>(), sent))
    respond(HttpStatusCode.fromValue(sent.httpStatusCode), sent)
}

/** Responds with a failure, for code that holds a [DomainError] rather than a `Resource`. */
suspend fun ApplicationCall.respondError(error: DomainError) = respondDefault(error.toDefaultResponse<Unit>())

/**
 * Answers [error] for [path] and everything under it, whatever the method: how a switched-off feature keeps one
 * stable code instead of the gateway's `request.not_found` (Spaces, archived 2026-11-05: `spaces.unavailable`).
 */
fun Route.unavailable(path: String, error: DomainError) {
    route(path) {
        handle { call.respondError(error) }
        route("{...}") { handle { call.respondError(error) } }
    }
}
