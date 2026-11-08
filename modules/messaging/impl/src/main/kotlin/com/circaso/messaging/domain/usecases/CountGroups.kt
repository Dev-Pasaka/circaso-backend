package com.circaso.messaging.domain.usecases

import com.circaso.messaging.domain.repositories.GroupReportRepository
import com.circaso.shared.Resource

/** Staff: how many groups there are (decision 2026-11-08), counted on the partial index of groups. */
class CountGroups(private val reports: GroupReportRepository) {
    suspend operator fun invoke(): Resource<Long> = Resource.success(reports.count())
}
