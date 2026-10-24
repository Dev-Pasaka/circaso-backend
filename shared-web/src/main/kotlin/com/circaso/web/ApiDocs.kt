package com.circaso.web

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.openapi.describe
import kotlinx.serialization.Serializable
import kotlin.reflect.typeOf

/** Stands for "nothing" in a route's request or response: the call takes no body, or answers with `data: null`. */
@Serializable
data class NoBody(val unused: String? = null)

/**
 * Describes a route for the OpenAPI spec: what it does, its module's tag, the request body it takes and the
 * [DefaultResponse] it answers with, and the error codes it can return. Every state change also lists the
 * `Idempotency-Key` header it requires. The reader at `/docs` is built from these descriptions, so a route that is
 * not described is not documented.
 */
inline fun <reified Req, reified Res> Route.docs(
    summary: String,
    tag: String,
    errors: List<String> = emptyList(),
    notes: String? = null,
    /** False for the few routes that answer with the body itself rather than the [DefaultResponse] envelope (health). */
    envelope: Boolean = true,
): Route {
    val stateChange =
        ((this as? RoutingNode)?.selector as? HttpMethodRouteSelector)?.method in
            setOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch, HttpMethod.Delete)
    val takesBody = Req::class != NoBody::class
    return describe {
        this.summary = summary
        this.description =
            buildString {
                notes?.let { appendLine(it).appendLine() }
                if (errors.isNotEmpty()) append("**Errors:** ").append(errors.joinToString(", ") { "`$it`" })
            }.trim().ifEmpty { null }
        tag(tag)
        if (stateChange) {
            parameters {
                header("Idempotency-Key") {
                    required = true
                    description =
                        "A UUID. A repeat within 24 hours returns the first response instead of running again."
                }
            }
        }
        if (takesBody) requestBody { schema = buildSchema(typeOf<Req>()) }
        responses {
            HttpStatusCode.OK {
                description =
                    if (envelope) {
                        "Always the DefaultResponse envelope; on a failure `status` is false and `code` names " +
                            "the error."
                    } else {
                        "The body " +
                            "itself, not the envelope."
                    }
                schema = if (envelope) buildSchema(typeOf<DefaultResponse<Res>>()) else buildSchema(typeOf<Res>())
            }
        }
    }
}
