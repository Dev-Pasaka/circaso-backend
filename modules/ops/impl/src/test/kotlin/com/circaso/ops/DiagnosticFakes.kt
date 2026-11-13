package com.circaso.ops

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import com.circaso.support.api.DiagnosticLogDesk
import com.circaso.support.api.DiagnosticLogLine
import com.circaso.support.api.DiagnosticLogText
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** support's diagnostic logs in memory. */
class FakeLogDesk : DiagnosticLogDesk {
    val logs = ConcurrentHashMap<UUID, DiagnosticLogText>()

    fun add(userId: UserId, code: String = "DL-7K3QX9"): DiagnosticLogLine {
        val line =
            DiagnosticLogLine(
                Uuid7.generate(),
                code,
                userId,
                "android",
                "1.4.2 (61)",
                "14",
                "Xiaomi 23021RAA2Y",
                52,
                EPOCH,
                EPOCH.plus(Duration.ofDays(30)),
            )
        logs[line.logId] = DiagnosticLogText(line, "2026-11-13T12:22:43Z calls: ended, cause=RECONNECT_GAVE_UP\n")
        return line
    }

    override suspend fun byCode(code: String) =
        Resource.success(logs.values.map { it.line }.filter { it.code == "DL-" + code.uppercase().removePrefix("DL-") })

    override suspend fun ofUser(userId: UserId, limit: Int) =
        Resource.success(logs.values.map { it.line }.filter { it.userId == userId }.take(limit))

    override suspend fun read(logId: UUID): Resource<DiagnosticLogText> =
        logs[logId]?.let { Resource.success(it) } ?: Resource.error(LogNotFound)

    private object LogNotFound : com.circaso.shared.DomainError {
        override val code = "support.log_not_found"
        override val message = "There's no diagnostic log with this id."
        override val kind = com.circaso.shared.ErrorKind.NOT_FOUND
    }
}
