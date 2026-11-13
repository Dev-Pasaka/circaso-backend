package com.circaso.ops.presentation.dto

import kotlinx.serialization.Serializable

/** A diagnostic log's line (2026-11-13): `code` is `DL-` and six characters; kept until `expiresAt`. */
@Serializable
data class DiagnosticLogRowDto(
    val logId: String,
    val code: String,
    val userId: String,
    val platform: String,
    val appVersion: String,
    val osVersion: String,
    val deviceModel: String,
    val sizeBytes: Int,
    val createdAt: String,
    val expiresAt: String,
)

@Serializable
data class DiagnosticLogListResponse(val items: List<DiagnosticLogRowDto>)

/** A whole diagnostic log: its line, and `log`, one note per line, oldest first. */
@Serializable
data class DiagnosticLogResponse(
    val logId: String,
    val code: String,
    val userId: String,
    val platform: String,
    val appVersion: String,
    val osVersion: String,
    val deviceModel: String,
    val sizeBytes: Int,
    val createdAt: String,
    val expiresAt: String,
    val log: String,
)
