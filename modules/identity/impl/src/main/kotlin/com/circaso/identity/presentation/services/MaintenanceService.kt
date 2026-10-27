package com.circaso.identity.presentation.services

import com.circaso.identity.domain.usecases.CompleteDeletions
import com.circaso.identity.domain.usecases.SweepPendingAccounts
import com.circaso.shared.Resource

/** What identity's scheduled jobs run: the scheduler is one more way in, so it goes through a service too. */
class MaintenanceService(
    private val sweepPendingAccounts: SweepPendingAccounts,
    private val completeDeletions: CompleteDeletions,
) {
    suspend fun sweepPendingAccounts(): Resource<Int> = sweepPendingAccounts.invoke()

    suspend fun completeDeletions(): Resource<Int> = completeDeletions.invoke()
}
