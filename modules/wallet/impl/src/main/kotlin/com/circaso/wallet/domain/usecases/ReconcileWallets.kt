package com.circaso.wallet.domain.usecases

import com.circaso.wallet.domain.models.JobSettings
import com.circaso.wallet.domain.models.ReconcilePass
import com.circaso.wallet.domain.ports.ActiveWallets
import com.circaso.wallet.domain.repositories.TransactionRepository
import com.circaso.wallet.domain.support.Backoff
import com.circaso.wallet.domain.support.Reconciler
import java.time.Clock
import java.time.Duration

/**
 * The doc's `ReconcileWallets`: Circle's transaction list compared with ours, through [Reconciler] (and so the
 * webhook's own path), filling what a lost webhook never told us.
 *
 * - [ReconcilePass.ACTIVE], `wallet.reconcile-active`, every 2 minutes: the users active in the last 30 minutes, those
 *   who opened the wallet or a Circle session ([ActiveWallets]) and those with a history row on either side, over
 *   Circle's last hour. Money that came in while the webhook was lost shows within minutes for anyone looking.
 * - [ReconcilePass.DAILY], `wallet.reconcile-daily`: every user with a history row on either side that day, over the
 *   last 26 hours.
 *
 * Returns how many Circle transactions were looked at.
 */
class ReconcileWallets(
    private val active: ActiveWallets,
    private val transactions: TransactionRepository,
    private val reconciler: Reconciler,
    private val settings: JobSettings,
    private val clock: Clock,
) {
    private val backoffs = ReconcilePass.entries.associateWith { Backoff() }

    suspend operator fun invoke(pass: ReconcilePass): Int {
        val now = clock.instant()
        return when (pass) {
            ReconcilePass.ACTIVE -> {
                val cutoff = now.minus(settings.activeWindow)
                val users =
                    (active.since(cutoff) + transactions.parties(cutoff, settings.activeBatch))
                        .distinct()
                        .take(settings.activeBatch)
                reconciler.reconcile(
                    ACTIVE_JOB,
                    users,
                    now.minus(settings.activeLookback),
                    settings.transactionsPerUser,
                    backoffs.getValue(pass),
                )
            }

            ReconcilePass.DAILY -> {
                val users = transactions.parties(now.minus(DAY), settings.dailyBatch)
                reconciler.reconcile(
                    DAILY_JOB,
                    users,
                    now.minus(settings.dailyLookback),
                    settings.transactionsPerUser,
                    backoffs.getValue(pass),
                )
            }
        }
    }

    private companion object {
        const val ACTIVE_JOB = "wallet.reconcile-active"
        const val DAILY_JOB = "wallet.reconcile-daily"
        val DAY: Duration = Duration.ofDays(1)
    }
}
