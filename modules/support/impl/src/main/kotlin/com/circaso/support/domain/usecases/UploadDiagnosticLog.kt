package com.circaso.support.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.Uuid7
import com.circaso.support.domain.errors.SupportError
import com.circaso.support.domain.models.DiagnosticLog
import com.circaso.support.domain.models.NewDiagnosticLog
import com.circaso.support.domain.ports.LogAllowance
import com.circaso.support.domain.repositories.DiagnosticLogRepository
import com.circaso.support.domain.support.DiagnosticCodes
import com.circaso.support.domain.support.LogScrub
import java.time.Clock
import java.time.Duration

/**
 * SU5 `POST /support/diagnostics` (2026-11-13): stores the phone's recent technical log, scrubbed again, and answers
 * it with a code the person gives support (`DL-7K3QX9`). At most [MAX_PER_HOUR] an hour per person; kept [RETENTION].
 * The log itself is never written to the server's own log.
 */
class UploadDiagnosticLog(
    private val logs: DiagnosticLogRepository,
    private val allowance: LogAllowance,
    private val clock: Clock,
) {
    suspend operator fun invoke(upload: NewDiagnosticLog): Resource<DiagnosticLog> {
        invalid(upload)?.let { return Resource.error(it) }
        allowance
            .take(
                upload.userId,
                MAX_PER_HOUR,
                WINDOW,
            )?.let { return Resource.error(SupportError.LogRateLimited(it)) }
        val now = clock.instant()
        val text = LogScrub.scrub(upload.log)
        repeat(CODE_ATTEMPTS) {
            val log =
                DiagnosticLog(
                    logId = Uuid7.generate(now),
                    code = DiagnosticCodes.next(),
                    userId = upload.userId,
                    platform = upload.platform.trim().lowercase(),
                    appVersion = upload.appVersion.trim(),
                    osVersion = upload.osVersion.trim(),
                    deviceModel = upload.deviceModel.trim().ifEmpty { "unknown" },
                    sizeBytes = text.encodeToByteArray().size,
                    log = text,
                    createdAt = now,
                    expiresAt = now.plus(RETENTION),
                )
            if (logs.insert(log)) return Resource.success(log)
        }
        error("no free diagnostic log code after $CODE_ATTEMPTS draws")
    }

    private fun invalid(upload: NewDiagnosticLog): SupportError? =
        when {
            upload.platform.isBlank() || upload.platform.length > 16 -> field("platform", "1 to 16 characters")
            upload.appVersion.isBlank() || upload.appVersion.length > 40 -> field("appVersion", "1 to 40 characters")
            upload.osVersion.isBlank() || upload.osVersion.length > 40 -> field("osVersion", "1 to 40 characters")
            upload.deviceModel.length > 80 -> field("deviceModel", "at most 80 characters")
            upload.log.isBlank() -> field("log", "required")
            upload.log.encodeToByteArray().size > MAX_LOG_BYTES -> field("log", "at most 2 MiB")
            else -> null
        }

    private fun field(name: String, problem: String) = SupportError.LogInvalid(name, problem)

    companion object {
        const val MAX_LOG_BYTES = 2 * 1024 * 1024
        private const val MAX_PER_HOUR = 5L
        private const val CODE_ATTEMPTS = 5
        private val WINDOW: Duration = Duration.ofHours(1)
        val RETENTION: Duration = Duration.ofDays(30)
    }
}
