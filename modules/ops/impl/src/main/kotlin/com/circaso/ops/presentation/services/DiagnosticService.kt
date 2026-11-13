package com.circaso.ops.presentation.services

import com.circaso.ops.domain.models.Actor
import com.circaso.ops.domain.usecases.FindDiagnosticLogs
import com.circaso.ops.domain.usecases.OpenDiagnosticLog
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.support.api.DiagnosticLogLine
import com.circaso.support.api.DiagnosticLogText
import java.util.UUID

class DiagnosticService(
    private val findDiagnosticLogs: FindDiagnosticLogs,
    private val openDiagnosticLog: OpenDiagnosticLog,
) {
    suspend fun find(
        code: String?,
        userId: UserId?,
        limit: Int,
    ): Resource<List<DiagnosticLogLine>> = findDiagnosticLogs(code, userId, limit)

    suspend fun open(actor: Actor, logId: UUID): Resource<DiagnosticLogText> = openDiagnosticLog(actor, logId)
}
