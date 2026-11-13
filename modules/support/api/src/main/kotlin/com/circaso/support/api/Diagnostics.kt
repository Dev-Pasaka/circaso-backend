package com.circaso.support.api

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Instant
import java.util.UUID

/**
 * A diagnostic log a phone sent from You → Help → "Send diagnostic log" (SU5, 2026-11-13), as staff list it. [code] is
 * what the person reads out (`DL-7K3QX9`); [sizeBytes] the log's UTF-8 size. Kept 30 days ([expiresAt]).
 */
data class DiagnosticLogLine(
    val logId: UUID,
    val code: String,
    val userId: UserId,
    val platform: String,
    val appVersion: String,
    val osVersion: String,
    val deviceModel: String,
    val sizeBytes: Int,
    val createdAt: Instant,
    val expiresAt: Instant,
)

/** A whole diagnostic log: its line and the text, already scrubbed on the phone and again on the server. */
data class DiagnosticLogText(val line: DiagnosticLogLine, val log: String)

/**
 * The staff side of diagnostic logs, for the ops module (2026-11-13). Who may read them and the audit line for opening
 * one are the ops module's; this only finds and reads them.
 */
interface DiagnosticLogDesk {
    /** The log with [code] (any case, `DL-` optional); empty when there is none or it expired. */
    suspend fun byCode(code: String): Resource<List<DiagnosticLogLine>>

    /** [userId]'s logs, newest first, at most [limit]. */
    suspend fun ofUser(userId: UserId, limit: Int): Resource<List<DiagnosticLogLine>>

    /** Error: `support.log_not_found`. */
    suspend fun read(logId: UUID): Resource<DiagnosticLogText>
}
