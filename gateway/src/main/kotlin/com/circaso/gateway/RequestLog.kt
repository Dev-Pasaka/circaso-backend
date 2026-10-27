package com.circaso.gateway

import com.circaso.web.principalOrNull
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.request.httpMethod
import io.ktor.server.routing.RoutePathComponent
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.TrailingSlashRouteSelector
import io.ktor.util.AttributeKey
import net.logstash.logback.argument.StructuredArguments.kv
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.circaso.gateway.RequestLog")
private val StartedAt = AttributeKey<Long>("circaso.request.startedAt")
private val RouteTemplate = AttributeKey<String>("circaso.request.routeTemplate")

/** What `http.route` says for a request no route matched: its path is the caller's text, so it is never logged. */
internal const val UNMATCHED_ROUTE = "(unmatched)"

/**
 * One line per request: method, route, status, duration and the user id. The request id is already in every line
 * through the MDC. The route is the matched route's template (`/v1/handles/{handle}/availability`), never the
 * concrete path or the query string, because a path parameter can be a handle, an invite token or a phone number.
 * Never a token, a phone number or an amount.
 */
val RequestLog =
    createApplicationPlugin("CircasoRequestLog") {
        // Routing announces the route it picked; the template is kept on the call for the line written at the end.
        application.monitor.subscribe(RoutingRoot.RoutingCallStarted) { call ->
            call.attributes.put(RouteTemplate, call.route.template())
        }
        onCall { call -> call.attributes.put(StartedAt, System.nanoTime()) }
        on(ResponseSent) { call ->
            val started = call.attributes.getOrNull(StartedAt) ?: return@on
            val status = call.response.status()?.value ?: 0
            val line =
                arrayOf(
                    kv("kind", "request"),
                    kv("http.method", call.request.httpMethod.value),
                    kv("http.route", call.attributes.getOrNull(RouteTemplate) ?: UNMATCHED_ROUTE),
                    kv("http.status", status),
                    kv("durationMs", (System.nanoTime() - started) / 1_000_000),
                    kv("userId", call.principalOrNull()?.userId?.toString()),
                )
            if (status >= 500) log.warn("request", *line) else log.info("request", *line)
        }
    }

/** The route as it was declared: path constants and `{parameter}` names from the root down, nothing the caller sent. */
internal fun RoutingNode.template(): String {
    val segments =
        generateSequence(this) { it.parent }
            .mapNotNull { node ->
                node.selector.takeIf { it is RoutePathComponent && it != TrailingSlashRouteSelector }
            }.map { it.toString() }
            .filter { it.isNotEmpty() } // the root's own selector
            .toList()
            .asReversed()
    return "/" + segments.joinToString("/")
}
