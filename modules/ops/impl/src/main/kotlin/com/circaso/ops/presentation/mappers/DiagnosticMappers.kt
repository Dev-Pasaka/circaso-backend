package com.circaso.ops.presentation.mappers

import com.circaso.ops.presentation.dto.DiagnosticLogResponse
import com.circaso.ops.presentation.dto.DiagnosticLogRowDto
import com.circaso.support.api.DiagnosticLogLine
import com.circaso.support.api.DiagnosticLogText

fun DiagnosticLogLine.toDto() =
    DiagnosticLogRowDto(
        logId.toString(),
        code,
        userId.toString(),
        platform,
        appVersion,
        osVersion,
        deviceModel,
        sizeBytes,
        createdAt.toString(),
        expiresAt.toString(),
    )

fun DiagnosticLogText.toResponse() =
    DiagnosticLogResponse(
        line.logId.toString(),
        line.code,
        line.userId.toString(),
        line.platform,
        line.appVersion,
        line.osVersion,
        line.deviceModel,
        line.sizeBytes,
        line.createdAt.toString(),
        line.expiresAt.toString(),
        log,
    )
