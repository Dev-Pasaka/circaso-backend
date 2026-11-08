package com.circaso.opsgateway

import com.circaso.gateway.clientIp
import com.circaso.gateway.template
import com.circaso.web.staffOrNull
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.request.httpMethod
import io.ktor.server.routing.RoutingRoot
import io.ktor.util.AttributeKey
import net.logstash.logback.argument.StructuredArguments.kv
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.circaso.opsgateway.RequestLog")
private val StartedAt = AttributeKey<Long>("circaso.ops.startedAt")
private val RouteTemplate = AttributeKey<String>("circaso.ops.routeTemplate")
private val Logged = AttributeKey<Unit>("circaso.ops.logged")

/**
 * One line per staff request, like the app gateway's, marked `gateway=ops`: method, route template (never the path:
 * it can hold a user id someone searched for), status, duration, the staff id and role, the caller's address. Never
 * the staff email (personal data) and never a body. The health routes are left out.
 */
val OpsRequestLog =
    createApplicationPlugin("CircasoOpsRequestLog") {
        application.monitor.subscribe(RoutingRoot.RoutingCallStarted) { call ->
            call.attributes.put(RouteTemplate, call.route.template())
        }
        onCall { call -> call.attributes.put(StartedAt, System.nanoTime()) }
        on(ResponseSent) { call ->
            if (call.attributes.contains(Logged)) return@on
            call.attributes.put(Logged, Unit)
            val started = call.attributes.getOrNull(StartedAt) ?: return@on
            val route = call.attributes.getOrNull(RouteTemplate) ?: "(unmatched)"
            if (route.startsWith("/health/")) return@on
            val status = call.response.status()?.value ?: 0
            val staff = call.staffOrNull()
            val line =
                arrayOf(
                    kv("kind", "request"),
                    kv("gateway", "ops"),
                    kv("http.method", call.request.httpMethod.value),
                    kv("http.route", route),
                    kv("http.status", status),
                    kv("durationMs", (System.nanoTime() - started) / 1_000_000),
                    kv("staffId", staff?.staffId?.toString()),
                    kv("staff.role", staff?.role?.name),
                    kv("staff.signIn", staff?.signIn?.name),
                    kv("client.ip", call.clientIp()),
                )
            when {
                status >= 500 -> log.error("request", *line)
                status == 401 || status == 403 || status == 429 -> log.warn("request", *line)
                else -> log.info("request", *line)
            }
        }
    }
