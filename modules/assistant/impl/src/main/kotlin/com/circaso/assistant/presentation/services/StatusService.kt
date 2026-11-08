package com.circaso.assistant.presentation.services

import com.circaso.assistant.domain.models.AshaStatus
import com.circaso.assistant.domain.usecases.GetAshaStatus
import com.circaso.assistant.domain.usecases.MonitorAshaHealth
import com.circaso.shared.Resource

/** Asha's health (decision 2026-11-07): her status for the app (A14), and the monitor's tick. */
class StatusService(private val getAshaStatus: GetAshaStatus, private val monitorAshaHealth: MonitorAshaHealth) {
    suspend fun status(): Resource<AshaStatus> = getAshaStatus()

    suspend fun monitor(): Resource<Int> = monitorAshaHealth()
}
