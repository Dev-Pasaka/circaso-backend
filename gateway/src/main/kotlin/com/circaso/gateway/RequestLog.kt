package com.circaso.gateway

import com.circaso.web.principalOrNull
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.util.AttributeKey
import net.logstash.logback.argument.StructuredArguments.kv
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.circaso.gateway.RequestLog")
private val StartedAt = AttributeKey<Long>("circaso.request.startedAt")
private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

/**
 * One line per request: method, route, status, duration and the user id. The request id is already in every line
 * through the MDC. Never the query string, a token, a phone number or an amount. Ids in the path are replaced by
 * `{id}`, so the line names a route rather than a person.
 */
val RequestLog =
    createApplicationPlugin("CircasoRequestLog") {
        onCall { call -> call.attributes.put(StartedAt, System.nanoTime()) }
        on(ResponseSent) { call ->
            val started = call.attributes.getOrNull(StartedAt) ?: return@on
            val status = call.response.status()?.value ?: 0
            val line =
                arrayOf(
                    kv("kind", "request"),
                    kv("http.method", call.request.httpMethod.value),
                    kv("http.route", uuid.replace(call.request.path(), "{id}")),
                    kv("http.status", status),
                    kv("durationMs", (System.nanoTime() - started) / 1_000_000),
                    kv("userId", call.principalOrNull()?.userId?.toString()),
                )
            if (status >= 500) log.warn("request", *line) else log.info("request", *line)
        }
    }
