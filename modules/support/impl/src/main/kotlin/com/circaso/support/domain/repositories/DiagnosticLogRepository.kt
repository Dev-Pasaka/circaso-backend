package com.circaso.support.domain.repositories

import com.circaso.shared.UserId
import com.circaso.support.domain.models.DiagnosticLog
import java.util.UUID

/** `support.diagnostic_logs` (SU5, 2026-11-13). Lists read the line only, never the text. */
interface DiagnosticLogRepository {
    /** Stores [log]; false when its code is already taken (the caller draws another). */
    suspend fun insert(log: DiagnosticLog): Boolean

    /** The unexpired log with [code], without its text. */
    suspend fun byCode(code: String): DiagnosticLog?

    /** [userId]'s unexpired logs, newest first, without their text. */
    suspend fun ofUser(userId: UserId, limit: Int): List<DiagnosticLog>

    /** The whole unexpired log. */
    suspend fun find(logId: UUID): DiagnosticLog?

    /** Every log of [userId]; how many went. */
    suspend fun deleteOfUser(userId: UserId): Int
}
