package com.circaso.support.presentation.controllers

import com.circaso.shared.CircasoJson
import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.flatMap
import com.circaso.support.domain.errors.SupportError
import com.circaso.support.presentation.dto.DiagnosticLogReceiptResponse
import com.circaso.support.presentation.dto.DiagnosticLogRequest
import com.circaso.support.presentation.mappers.toNewLog
import com.circaso.support.presentation.mappers.toReceipt
import com.circaso.support.presentation.services.DiagnosticLogService
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import com.circaso.web.userId
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

/** A 2 MiB log as JSON, with its escapes and the device line: anything longer is refused unread. */
private const val MAX_BODY_BYTES = 3L * 1024 * 1024

/**
 * SU5 (2026-11-13): You → Help → "Send diagnostic log" uploads the phone's rolling technical log so support can see
 * why something failed. The answer is a code to read out (`DL-7K3QX9`); staff find the log by it in the admin portal.
 */
fun Route.diagnosticLogController(logs: DiagnosticLogService) {
    route("/support/diagnostics") {
        authenticate(Scope.APP) {
            post {
                val caller = call.userId()
                val result = call.boundedRequest().flatMap { logs.upload(it.toNewLog(caller)) }
                call.respondDefault(result.toDefaultResponse { it.toReceipt() })
            }.docs<DiagnosticLogRequest, DiagnosticLogReceiptResponse>(
                "SU5 · Send a diagnostic log",
                "Support tickets",
                listOf("support.log_invalid", "support.log_rate_limited", "request.malformed"),
                notes =
                    "Scope: `app`. Send an `Idempotency-Key`. The phone's recent technical log, already scrubbed (no " +
                        "message text, names, phone numbers, emails, tokens or keys; the server scrubs it again). " +
                        "`log` at most 2 MiB, the body at most 3 MiB. At most 5 an hour (`support.log_rate_limited` " +
                        "with `retryAfterSeconds`). Answers `code` (`DL-` and six characters) to give support, and " +
                        "`expiresAt`: kept 30 days.",
            )
        }
    }
}

/** The body read no further than [MAX_BODY_BYTES] plus one; too long or none is `support.log_invalid`. */
private suspend fun ApplicationCall.boundedRequest(): Resource<DiagnosticLogRequest> {
    val bytes = receiveChannel().readRemaining(MAX_BODY_BYTES + 1).readByteArray()
    return when {
        bytes.size > MAX_BODY_BYTES -> Resource.error(SupportError.LogInvalid("body", "at most 3 MiB"))
        bytes.isEmpty() -> Resource.error(SupportError.LogInvalid("body", "required"))
        else -> Resource.success(CircasoJson.decodeFromString(DiagnosticLogRequest.serializer(), bytes.decodeToString()))
    }
}
