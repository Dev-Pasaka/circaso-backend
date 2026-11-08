package com.circaso.gateway

import com.circaso.web.principalOrNull
import io.ktor.http.HttpStatusCode
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
private val Logged = AttributeKey<Unit>("circaso.request.logged")

/** What `http.route` says for a request no route matched: its path is the caller's text, so it is never logged. */
internal const val UNMATCHED_ROUTE = "(unmatched)"

/** `/health/live` and `/health/ready`, which the request log leaves out. */
private const val HEALTH_ROUTES = "/health/"

/**
 * One line per request: method, route, status, duration, the user id, and who sent it (address, country, app version,
 * device and User-Agent; see [clientFields]). The request id is already in every line
 * through the MDC. The route is the matched route's template (`/v1/handles/{handle}/availability`), never the
 * concrete path or the query string, because a path parameter can be a handle, an invite token or a phone number.
 * Never a token, a phone number or an amount. The health routes are left out: the container, the deploy and the load
 * balancer probe them every few seconds, and `/health/ready` answers `503` all through the start-up warm-up. So is a
 * WebSocket that opened (`101`): its lines are the socket hub's `ws.open` and `ws.close`.
 */
val RequestLog =
    createApplicationPlugin("CircasoRequestLog") {
        // Routing announces the route it picked; the template is kept on the call for the line written at the end.
        application.monitor.subscribe(RoutingRoot.RoutingCallStarted) { call ->
            call.attributes.put(RouteTemplate, call.route.template())
        }
        onCall { call -> call.attributes.put(StartedAt, System.nanoTime()) }
        on(ResponseSent) { call ->
            // StatusPages answers an unmatched route by responding again from inside the first response's send, so
            // ResponseSent fires once for its envelope and once more for the response it replaced: log the first.
            if (call.attributes.contains(Logged)) return@on
            call.attributes.put(Logged, Unit)
            val started = call.attributes.getOrNull(StartedAt) ?: return@on
            val route = call.attributes.getOrNull(RouteTemplate) ?: UNMATCHED_ROUTE
            if (route.startsWith(HEALTH_ROUTES)) return@on
            val status = call.response.status()?.value ?: 0
            // A socket's `101` is sent when it opens, but this fires when it closes: its "duration" would be the
            // socket's whole life. The socket hub writes `ws.open` and `ws.close` instead.
            if (status == HttpStatusCode.SwitchingProtocols.value) return@on
            val client = call.clientFields()
            val line =
                arrayOf(
                    kv("kind", "request"),
                    kv("http.method", call.request.httpMethod.value),
                    kv("http.route", route),
                    kv("http.status", status),
                    kv("durationMs", (System.nanoTime() - started) / 1_000_000),
                    kv("userId", call.principalOrNull()?.userId?.toString()),
                    kv("client.ip", client.ip),
                    kv("client.country", client.country),
                    kv("client.version", client.version),
                    kv("client.device", client.device),
                    kv("client.ua", client.userAgent),
                )
            if (status >= 500) log.warn("request", *line) else log.info("request", *line)
        }
    }

/** The route as it was declared: path constants and `{parameter}` names from the root down, nothing the caller sent. */
fun RoutingNode.template(): String {
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
