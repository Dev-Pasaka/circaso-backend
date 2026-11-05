package com.circaso.wallet.presentation.services

import com.circaso.wallet.domain.models.ReconcilePass
import com.circaso.wallet.domain.usecases.CheckStuckTransfers
import com.circaso.wallet.domain.usecases.ExpireUnsignedTransfers
import com.circaso.wallet.domain.usecases.ReconcileWallets
import com.circaso.wallet.domain.usecases.SettleSplitPayments
import com.circaso.wallet.domain.usecases.SweepWalletSetups

/**
 * The wallet's scheduled jobs (Wallet → Data → Scheduled jobs), the safety net for a lost Circle webhook. Each returns
 * how many items it handled, for the scheduler's log line.
 */
class WalletJobsService(
    private val expireUnsignedTransfers: ExpireUnsignedTransfers,
    private val checkStuckTransfers: CheckStuckTransfers,
    private val reconcileWallets: ReconcileWallets,
    private val settleSplitPayments: SettleSplitPayments,
    private val sweepWalletSetups: SweepWalletSetups,
) {
    suspend fun expireUnsigned(): Int = expireUnsignedTransfers()

    suspend fun checkStuck(): Int = checkStuckTransfers()

    suspend fun reconcile(pass: ReconcilePass): Int = reconcileWallets(pass)

    suspend fun settleSplits(): Int = settleSplitPayments()

    suspend fun sweepSetups(): Int = sweepWalletSetups()
}
