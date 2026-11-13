package com.circaso.support.domain.models

import com.circaso.shared.UserId
import java.time.Instant
import java.util.UUID

/** SU5's upload as the domain takes it: the phone's device line and its log, checked by [UploadDiagnosticLog]. */
data class NewDiagnosticLog(
    val userId: UserId,
    val platform: String,
    val appVersion: String,
    val osVersion: String,
    val deviceModel: String,
    val log: String,
)

/** A stored diagnostic log; [log] is empty when only the line was read (lists never load the text). */
data class DiagnosticLog(
    val logId: UUID,
    val code: String,
    val userId: UserId,
    val platform: String,
    val appVersion: String,
    val osVersion: String,
    val deviceModel: String,
    val sizeBytes: Int,
    val log: String,
    val createdAt: Instant,
    val expiresAt: Instant,
)
