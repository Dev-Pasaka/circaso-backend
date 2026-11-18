package com.circaso.wallet.presentation.services

import com.circaso.wallet.domain.models.ReconcilePass
import com.circaso.wallet.domain.usecases.CheckStuckTransfers
import com.circaso.wallet.domain.usecases.ExpireUnsignedTransfers
import com.circaso.wallet.domain.usecases.ReconcileWallets
import com.circaso.wallet.domain.usecases.SettleSplitPayments
import com.circaso.wallet.domain.usecases.SweepWalletSetups
import com.circaso.shared.Network
import com.circaso.shared.WalletNetwork
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

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
    /** The networks custody can reach: every job runs once for each (decision 2026-11-18). */
    private val networks: () -> Collection<Network> = { listOf(Network.TESTNET) },
    /** Reads the network settings when they are stale, so a job works on the chains staff set last. */
    private val refreshSettings: suspend () -> Unit = {},
) {
    suspend fun expireUnsigned(): Int = onEachNetwork { expireUnsignedTransfers() }

    suspend fun checkStuck(): Int = onEachNetwork { checkStuckTransfers() }

    suspend fun reconcile(pass: ReconcilePass): Int = onEachNetwork { reconcileWallets(pass) }

    suspend fun settleSplits(): Int = onEachNetwork { settleSplitPayments() }

    suspend fun sweepSetups(): Int = onEachNetwork { sweepWalletSetups() }

    /**
     * Runs [job] once per network, with that network as the coroutine's: its rows, its Redis keys, its Circle account.
     * A network keeps being looked after while it is switched off (a transfer signed before must still settle), and
     * one failing doesn't stop the other; the first failure is thrown once both have run, for the scheduler's log.
     */
    private suspend fun onEachNetwork(job: suspend () -> Int): Int {
        refreshSettings()
        var handled = 0
        var failure: Exception? = null
        for (network in networks()) {
            try {
                handled += withContext(WalletNetwork(network)) { job() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = failure ?: e
            }
        }
        failure?.let { throw it }
        return handled
    }
}
