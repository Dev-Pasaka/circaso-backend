package com.circaso.wallet.domain.usecases

import com.circaso.wallet.domain.models.JobSettings
import com.circaso.wallet.domain.models.Transfer
import com.circaso.wallet.domain.models.TransferChange
import com.circaso.wallet.domain.models.TransferState
import com.circaso.wallet.domain.repositories.TransferRepository
import com.circaso.wallet.domain.support.OutgoingUpdates
import java.time.Clock
import java.time.Instant

/**
 * `wallet.expire-unsigned`, every minute: a transfer still `AWAITING_SIGNATURE` when its 15-minute signing window
 * closed is `EXPIRED` through [OutgoingUpdates.expire] (the transition guard, its `OUT` row, its amount back to the
 * daily limit, `transfer.updated` to the sender). If Circle later says it was signed after all, its update still
 * applies (`EXPIRED` → `SUBMITTED`/`CONFIRMED`). Returns how many expired.
 */
class ExpireUnsignedTransfers(
    private val transfers: TransferRepository,
    private val outgoing: OutgoingUpdates,
    private val settings: JobSettings,
    private val clock: Clock,
) {
    suspend operator fun invoke(): Int {
        val now = clock.instant()
        val due =
            transfers.inState(
                TransferState.AWAITING_SIGNATURE,
                createdAfter = Instant.EPOCH,
                createdBefore = now.minus(Transfer.SIGNING_WINDOW),
                limit = settings.transferBatch,
            )
        return due.count { outgoing.expire(it, TransferChange(at = now)) }
    }
}
