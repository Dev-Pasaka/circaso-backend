package com.circaso.support.presentation.dto

import kotlinx.serialization.Serializable

/**
 * SU5 `POST /support/diagnostics` (2026-11-13): the phone's recent technical log, scrubbed on the phone. Every field is
 * checked by the server (`support.log_invalid` names the one that is wrong), so all are optional here.
 */
@Serializable
data class DiagnosticLogRequest(
    /** `android` or `ios`, at most 16 characters. */
    val platform: String = "",
    /** At most 40 characters, for example `1.4.2 (61)`. */
    val appVersion: String = "",
    /** At most 40 characters. */
    val osVersion: String = "",
    /** At most 80 characters. */
    val deviceModel: String = "",
    /** The log, at most 2 MiB as UTF-8; one note per line. */
    val log: String = "",
)

/** The code to give support, and until when the log is kept. */
@Serializable
data class DiagnosticLogReceiptResponse(val code: String, val expiresAt: String)
