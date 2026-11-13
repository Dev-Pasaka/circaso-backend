package com.circaso.ops.presentation.controllers

import com.circaso.ops.domain.models.Permission
import com.circaso.ops.domain.support.OpsSettings
import com.circaso.ops.presentation.dto.DiagnosticLogListResponse
import com.circaso.ops.presentation.dto.DiagnosticLogResponse
import com.circaso.ops.presentation.mappers.FORBIDDEN
import com.circaso.ops.presentation.mappers.MALFORMED
import com.circaso.ops.presentation.mappers.TAG_DIAGNOSTICS
import com.circaso.ops.presentation.mappers.actor
import com.circaso.ops.presentation.mappers.pageLimit
import com.circaso.ops.presentation.mappers.parseUserId
import com.circaso.ops.presentation.mappers.parseUuid
import com.circaso.ops.presentation.mappers.query
import com.circaso.ops.presentation.mappers.roles
import com.circaso.ops.presentation.mappers.toDto
import com.circaso.ops.presentation.mappers.toResponse
import com.circaso.ops.presentation.services.DiagnosticService
import com.circaso.shared.Resource
import com.circaso.shared.flatMap
import com.circaso.web.NoBody
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.staffOnly
import com.circaso.web.toDefaultResponse
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * Diagnostic logs for SUPPORT and ADMIN (2026-11-13, `diagnostics.read`): find one by the code the person was shown
 * (`DL-7K3QX9`) or by the person, then read it. Opening a log is audited (`diagnostics.read`).
 */
fun Route.diagnosticController(logs: DiagnosticService, settings: OpsSettings) {
    route("/ops/diagnostics") {
        staffOnly(Permission.DIAGNOSTICS_READ.roles) {
            get {
                val code = call.query("code")
                val user = call.query("userId")?.let { parseUserId(it) } ?: Resource.success(null)
                val result =
                    user.flatMap { userId ->
                        call.pageLimit(settings).flatMap { logs.find(code, userId, it) }
                    }
                call.respondDefault(
                    result.toDefaultResponse { found -> DiagnosticLogListResponse(found.map { it.toDto() }) },
                )
            }.docs<NoBody, DiagnosticLogListResponse>(
                "OPS · Find diagnostic logs",
                TAG_DIAGNOSTICS,
                listOf(FORBIDDEN, MALFORMED),
                notes =
                    "${roles("SUPPORT", "ADMIN")} `code` (any case, `DL-` optional) finds that one log; otherwise " +
                        "`userId` lists the person's, newest first (`limit`). Lines only, never the text. Logs are " +
                        "kept 30 days.",
            )

            get("{logId}") {
                val result = parseUuid(call.parameters["logId"], "logId").flatMap { logs.open(call.actor(), it) }
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<NoBody, DiagnosticLogResponse>(
                "OPS · Read a diagnostic log",
                TAG_DIAGNOSTICS,
                listOf("ops.diagnostic_log_not_found", FORBIDDEN, MALFORMED),
                notes =
                    "${roles("SUPPORT", "ADMIN")} The whole log, scrubbed on the phone and on the server. Audited " +
                        "(`diagnostics.read`, the log's code and whose it is).",
            )
        }
    }
}
