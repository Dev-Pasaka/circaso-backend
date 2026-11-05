package com.circaso.wallet.domain.usecases

import com.circaso.integrations.api.CustodyOperation
import com.circaso.integrations.api.StablecoinCustody
import com.circaso.shared.ErrorKind
import com.circaso.shared.Resource
import com.circaso.wallet.domain.models.JobSettings
import com.circaso.wallet.domain.models.Transfer
import com.circaso.wallet.domain.ports.WalletWarnings
import com.circaso.wallet.domain.repositories.TransferRepository
import com.circaso.wallet.domain.repositories.WalletRepository
import com.circaso.wallet.domain.support.Backoff
import com.circaso.wallet.domain.support.CustodyUpdates
import java.time.Clock

/**
 * `wallet.unsettled-splits`: the safety net for a split payment (phase 2F) whose batch Circle never tied back to it.
 * Each split payment no Circle transaction has moved yet ([TransferRepository.unsettledSplits]: awaiting a signature
 * or expired), prepared at least [JobSettings.splitAfter] ago and at most [JobSettings.splitMaxAge], is looked for
 * among its sender's transactions at Circle; every contract execution of its wallet goes through [CustodyUpdates],
 * the webhook's own path, which matches it by its legs and settles it (`CONFIRMED` or `FAILED`, the `OUT` row, the fee
 * ledger, the event, the frames and the push), exactly once.
 *
 * One Circle read per sender, at most [JobSettings.transferBatch] transfers per run; the first time Circle can't be
 * reached the run stops and the job backs off ([Backoff]). Returns how many transfers it settled.
 */
class SettleSplitPayments(
    private val transfers: TransferRepository,
    private val wallets: WalletRepository,
    private val custody: StablecoinCustody,
    private val updates: CustodyUpdates,
    private val warnings: WalletWarnings,
    private val settings: JobSettings,
    private val clock: Clock,
) {
    private val backoff = Backoff()

    suspend operator fun invoke(): Int {
        if (backoff.skip()) return 0
        val now = clock.instant()
        val open =
            transfers.unsettledSplits(
                senderWalletId = null,
                createdAfter = now.minus(settings.splitMaxAge),
                createdBefore = now.minus(settings.splitAfter),
                limit = settings.transferBatch,
            )
        if (open.isEmpty()) return 0
        for ((sender, theirs) in open.groupBy { it.senderId }) {
            val user = wallets.circleUser(sender) ?: continue
            val since = theirs.minOf { it.createdAt }.minus(settings.splitAfter)
            val listed =
                when (
                    val read =
                        custody.transactions(
                            user.circleUserId.toString(),
                            since,
                            settings.transactionsPerUser,
                        )
                ) {
                    is Resource.Success -> {
                        read.data
                    }

                    is Resource.Error -> {
                        if (read.error.kind != ErrorKind.UPSTREAM) continue
                        warnings.jobBackingOff(JOB, backoff.failed())
                        return settled(open)
                    }
                }
            val walletIds = theirs.mapTo(HashSet()) { it.senderWalletId }
            val executions =
                listed
                    .filter { it.operation == CustodyOperation.CONTRACT_EXECUTION && it.walletId in walletIds }
                    .sortedBy { it.updatedAt }
            for (execution in executions) {
                if (updates.apply(execution) is Resource.Error) {
                    warnings.jobBackingOff(JOB, backoff.failed())
                    return settled(open)
                }
            }
        }
        backoff.succeeded()
        return settled(open)
    }

    /** How many of [open] some Circle transaction has moved now. */
    private suspend fun settled(open: List<Transfer>): Int =
        open.count { transfer -> transfers.find(transfer.transferId)?.circleTxId != null }

    private companion object {
        const val JOB = "wallet.unsettled-splits"
    }
}
