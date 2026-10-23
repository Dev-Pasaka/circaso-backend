package com.circaso.web

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.opentelemetry.api.trace.Span
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import java.util.UUID

/** The MDC key every log line's request id is read from (Logging → What a line carries). */
const val REQUEST_ID_MDC_KEY = "requestId"

/**
 * The request id is the OpenTelemetry trace id, so a support ticket leads to the trace and its logs. Without
 * a tracing agent (local runs, tests) there is no valid span, so a random 32-character id stands in.
 * The id is never taken from the client.
 */
fun newRequestId(): String {
    val context = Span.current().spanContext
    return if (context.isValid) context.traceId else UUID.randomUUID().toString().replace("-", "")
}

/**
 * Gives every call a request id (`call.callId`, returned in the envelope) and puts it in the logging MDC,
 * so every line written while serving the call, in any module and on any coroutine thread, carries it.
 */
fun Application.installRequestId() {
    install(CallId) {
        generate { newRequestId() }
        verify { true }
    }
    intercept(ApplicationCallPipeline.Plugins) {
        val requestId = call.callId
        if (requestId == null) {
            proceed()
        } else {
            withContext(MDCContext(mapOf(REQUEST_ID_MDC_KEY to requestId))) { proceed() }
        }
    }
}
