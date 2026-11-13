package com.circaso.ops.domain.usecases

import com.circaso.ops.domain.errors.OpsError
import com.circaso.ops.domain.models.Actor
import com.circaso.ops.domain.models.AuditAction
import com.circaso.ops.domain.support.AuditTrail
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.support.api.DiagnosticLogDesk
import com.circaso.support.api.DiagnosticLogLine
import com.circaso.support.api.DiagnosticLogText
import java.util.UUID

// Diagnostic logs for SUPPORT and ADMIN (2026-11-13, `diagnostics.read`): a phone's technical log, sent by the person
// from You → Help. Finding one by its code or the person reads only the lines; opening one is audited.

class FindDiagnosticLogs(private val logs: DiagnosticLogDesk) {
    /** By [code] (any case, `DL-` optional) when given, else [userId]'s newest [limit]. */
    suspend operator fun invoke(
        code: String?,
        userId: UserId?,
        limit: Int,
    ): Resource<List<DiagnosticLogLine>> =
        when {
            code != null -> logs.byCode(code)
            userId != null -> logs.ofUser(userId, limit)
            else -> Resource.success(emptyList())
        }
}

/** The whole log; the audit line names its code and whose it is. */
class OpenDiagnosticLog(private val logs: DiagnosticLogDesk, private val trail: AuditTrail) {
    suspend operator fun invoke(actor: Actor, logId: UUID): Resource<DiagnosticLogText> =
        trail.record(
            actor,
            AuditAction.DIAGNOSTICS_READ,
            logId.toString(),
            outcome = { "${it.line.code} of user ${it.line.userId}" },
        ) {
            when (val read = logs.read(logId)) {
                is Resource.Success -> {
                    read
                }

                is Resource.Error -> {
                    if (read.error.code ==
                        "support.log_not_found"
                    ) {
                        Resource.error(OpsError.DiagnosticLogNotFound)
                    } else {
                        read
                    }
                }
            }
        }
}
