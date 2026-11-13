package com.circaso.support.presentation.mappers

import com.circaso.shared.UserId
import com.circaso.support.api.DiagnosticLogLine
import com.circaso.support.api.DiagnosticLogText
import com.circaso.support.domain.models.DiagnosticLog
import com.circaso.support.domain.models.NewDiagnosticLog
import com.circaso.support.presentation.dto.DiagnosticLogReceiptResponse
import com.circaso.support.presentation.dto.DiagnosticLogRequest

fun DiagnosticLogRequest.toNewLog(userId: UserId) =
    NewDiagnosticLog(userId, platform, appVersion, osVersion, deviceModel, log)

fun DiagnosticLog.toReceipt() = DiagnosticLogReceiptResponse(code, expiresAt.toString())

fun DiagnosticLog.toLine() =
    DiagnosticLogLine(
        logId,
        code,
        userId,
        platform,
        appVersion,
        osVersion,
        deviceModel,
        sizeBytes,
        createdAt,
        expiresAt,
    )

fun DiagnosticLog.toText() = DiagnosticLogText(toLine(), log)
