package com.circaso.support.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.support.domain.errors.SupportError
import com.circaso.support.domain.models.DiagnosticLog
import com.circaso.support.domain.repositories.DiagnosticLogRepository
import com.circaso.support.domain.support.DiagnosticCodes
import java.util.UUID

// The staff side of diagnostic logs (2026-11-13), through DiagnosticLogDesk for the ops module.

/** The log a person was given the code of: any case, `DL-` optional. Empty when the code can't be one. */
class FindDiagnosticLog(private val logs: DiagnosticLogRepository) {
    suspend operator fun invoke(code: String): Resource<List<DiagnosticLog>> {
        val normal = DiagnosticCodes.normalize(code) ?: return Resource.success(emptyList())
        return Resource.success(listOfNotNull(logs.byCode(normal)))
    }
}

/** A person's logs, newest first. */
class ListUserDiagnosticLogs(private val logs: DiagnosticLogRepository) {
    suspend operator fun invoke(userId: UserId, limit: Int): Resource<List<DiagnosticLog>> =
        Resource.success(logs.ofUser(userId, limit))
}

/** One whole log. */
class ReadDiagnosticLog(private val logs: DiagnosticLogRepository) {
    suspend operator fun invoke(logId: UUID): Resource<DiagnosticLog> =
        logs.find(logId)?.let { Resource.success(it) } ?: Resource.error(SupportError.LogNotFound)
}
