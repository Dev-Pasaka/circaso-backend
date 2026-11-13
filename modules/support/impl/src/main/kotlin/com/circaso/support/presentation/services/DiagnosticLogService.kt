package com.circaso.support.presentation.services

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.support.domain.models.DiagnosticLog
import com.circaso.support.domain.models.NewDiagnosticLog
import com.circaso.support.domain.usecases.FindDiagnosticLog
import com.circaso.support.domain.usecases.ListUserDiagnosticLogs
import com.circaso.support.domain.usecases.ReadDiagnosticLog
import com.circaso.support.domain.usecases.UploadDiagnosticLog
import java.util.UUID

/** Diagnostic logs (2026-11-13): SU5 upload from the app, and the staff desk's find, list and read. */
class DiagnosticLogService(
    private val uploadDiagnosticLog: UploadDiagnosticLog,
    private val findDiagnosticLog: FindDiagnosticLog,
    private val listUserDiagnosticLogs: ListUserDiagnosticLogs,
    private val readDiagnosticLog: ReadDiagnosticLog,
) {
    suspend fun upload(log: NewDiagnosticLog): Resource<DiagnosticLog> = uploadDiagnosticLog(log)

    suspend fun byCode(code: String): Resource<List<DiagnosticLog>> = findDiagnosticLog(code)

    suspend fun ofUser(userId: UserId, limit: Int): Resource<List<DiagnosticLog>> = listUserDiagnosticLogs(userId, limit)

    suspend fun read(logId: UUID): Resource<DiagnosticLog> = readDiagnosticLog(logId)
}
