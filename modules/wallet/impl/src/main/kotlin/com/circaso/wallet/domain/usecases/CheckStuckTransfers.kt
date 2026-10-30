package com.circaso.wallet.domain.usecases

import com.circaso.integrations.api.StablecoinCustody
import com.circaso.shared.ErrorKind
import com.circaso.shared.Resource
import com.circaso.wallet.domain.models.JobSettings
import com.circaso.wallet.domain.models.Transfer
import com.circaso.wallet.domain.models.TransferState
import com.circaso.wallet.domain.ports.StuckNotices
import com.circaso.wallet.domain.ports.WalletWarnings
import com.circaso.wallet.domain.repositories.TransferRepository
import com.circaso.wallet.domain.repositories.WalletRepository
import com.circaso.wallet.domain.support.Backoff
import com.circaso.wallet.domain.support.CustodyUpdates
import java.time.Clock

/**
 * `wallet.stuck-transfers` (the doc's `StuckTransfers`): each `SUBMITTED` transfer older than
 * [JobSettings.stuckAfter] is read from Circle and its state applied through [CustodyUpdates], the webhook's own path,
 * so the transfer, its `OUT` row, its event, `transfer.updated` and the chat's payment card end exactly as if the
 * webhook had come; a webhook that comes after all changes nothing. One still `SUBMITTED` an hour after it was
 * prepared is reported once. Returns how many transfers Circle was asked about.
 *
 * Bounded by [JobSettings.transferBatch], newest first; the first time Circle can't be reached the run stops and the
 * job backs off ([Backoff]).
 */
class CheckStuckTransfers(
    private val transfers: TransferRepository,
    private val wallets: WalletRepository,
    private val custody: StablecoinCustody,
    private val updates: CustodyUpdates,
    private val notices: StuckNotices,
    private val warnings: WalletWarnings,
    private val settings: JobSettings,
    private val clock: Clock,
) {
    private val backoff = Backoff()

    suspend operator fun invoke(): Int {
        if (backoff.skip()) return 0
        val now = clock.instant()
        val submitted =
            transfers.inState(
                TransferState.SUBMITTED,
                createdAfter = now.minus(settings.stuckMaxAge),
                createdBefore = now.minus(settings.stuckAfter),
                limit = settings.transferBatch,
            )
        var asked = 0
        for (transfer in submitted) {
            when (check(transfer)) {
                null -> {
                    warnings.jobBackingOff(JOB, backoff.failed())
                    return asked
                }

                true -> {
                    asked++
                }

                false -> {
                    Unit
                }
            }
            if (now.isAfter(transfer.createdAt.plus(settings.stuckAlertAfter))) stillStuck(transfer)
        }
        backoff.succeeded()
        return asked
    }

    /** True if Circle was asked and its answer applied, false if there was nothing to ask, null if unreachable. */
    private suspend fun check(transfer: Transfer): Boolean? {
        // SUBMITTED comes from Circle's update, which carries its id; without one the reconciliation finds it by refId.
        val circleTxId = transfer.circleTxId ?: return false
        val user = wallets.circleUser(transfer.senderId) ?: return false
        val transaction =
            when (val read = custody.transaction(user.circleUserId.toString(), circleTxId)) {
                is Resource.Success -> read.data
                is Resource.Error -> return if (read.error.kind == ErrorKind.UPSTREAM) null else false
            }
        return if (updates.apply(transaction) is Resource.Error) null else true
    }

    private suspend fun stillStuck(transfer: Transfer) {
        val now = transfers.find(transfer.transferId) ?: return
        if (now.state != TransferState.SUBMITTED || !notices.claim(now.transferId)) return
        warnings.transferStuck(now.transferId.toString(), now.blockchain)
        // TODO(decision: how the sender is told a payment is taking longer) wallet.md says "tell the sender", but no
        //  frame or push for it exists yet (Realtime frames lists none); the app shows SUBMITTED until it moves.
    }

    private companion object {
        const val JOB = "wallet.stuck-transfers"
    }
}
