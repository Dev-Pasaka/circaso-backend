package com.circaso.assistant.domain.usecases

import com.circaso.assistant.domain.models.AshaStatus
import com.circaso.assistant.domain.ports.AshaHealth
import com.circaso.shared.Resource

/** A14: whether Asha can answer now, or is down for maintenance, and since when (decision 2026-11-07). */
class GetAshaStatus(private val health: AshaHealth) {
    suspend operator fun invoke(): Resource<AshaStatus> = Resource.success(health.status())
}
