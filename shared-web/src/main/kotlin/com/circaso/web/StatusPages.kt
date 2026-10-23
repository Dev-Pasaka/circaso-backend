package com.circaso.web

import com.circaso.shared.DomainException
import com.circaso.shared.SharedError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.statuspages.StatusPages
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.circaso.web.StatusPages")

/**
 * Turns anything thrown into the same envelope the routes return. Installed once, by the gateway.
 *
 * | Thrown | Response |
 * | --- | --- |
 * | `DomainException` | its error's kind, code and message |
 * | malformed JSON, missing field | 400 `request.malformed` |
 * | anything else | 500 `internal.error`; logged with the request id (from the MDC), never sent |
 */
fun Application.installStatusPages() {
    install(StatusPages) {
        exception<DomainException> { call, cause ->
            call.respondError(cause.error)
        }
        exception<BadRequestException> { call, _ -> call.respondError(SharedError.Malformed()) }
        exception<ContentTransformationException> { call, _ -> call.respondError(SharedError.Malformed()) }
        exception<SerializationException> { call, _ -> call.respondError(SharedError.Malformed()) }
        // An unknown route has no body; a route that answered with the envelope keeps its own error.
        status(HttpStatusCode.NotFound) { call, _ ->
            if (!call.attributes.contains(EnvelopeSent)) call.respondError(SharedError.NotFound)
        }
        exception<Throwable> { call, cause ->
            log.error("Unhandled exception", cause)
            call.respondError(SharedError.Internal)
        }
    }
}
