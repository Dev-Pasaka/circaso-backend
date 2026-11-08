package com.circaso.wallet.domain.support

import com.circaso.integrations.api.CustodyDirection
import com.circaso.integrations.api.CustodyOperation
import com.circaso.integrations.api.CustodyTransaction
import com.circaso.integrations.api.StablecoinCustody
import com.circaso.shared.Resource
import com.circaso.wallet.domain.models.Transfer
import com.circaso.wallet.domain.ports.WalletWarnings
import com.circaso.wallet.domain.repositories.TransferRepository
import java.math.BigInteger
import java.time.Clock
import java.time.Duration

/**
 * Finds the transfer a split payment's batch pays (phase 2F). The batch is one contract execution on the sender's
 * wallet, and Circle keeps no `refId` on a user's contract execution (2026-11-05: the record of a real payment had
 * none), so the update can't name its transfer. It is found:
 *
 * 1. by Circle's transaction id, once an earlier update of the same batch has been matched and stored it;
 * 2. else by its legs: Circle records each leg that lands in one of its wallets as an inbound transfer in the same
 *    chain transaction, from the sender's address. A transfer of that wallet no Circle transaction has moved yet
 *    matches when its fee leg (always Circaso's own wallet) is there and its payee leg is there too, or the payee's
 *    address has no Circle record at all (an address outside Circle). Of several that match, the newest is taken and
 *    the ambiguity reported.
 *
 * Before the chain has the batch (no `txHash` yet) there is nothing to match by; a later update, or
 * `wallet.unsettled-splits`, settles it.
 */
class SplitPayments(
    private val transfers: TransferRepository,
    private val custody: StablecoinCustody,
    private val warnings: WalletWarnings,
    private val clock: Clock,
) {
    /** The transfer [execution] pays; null when none matches. An error only when Circle couldn't be asked. */
    suspend fun transferOf(execution: CustodyTransaction): Resource<Transfer?> {
        if (execution.circleTxId.isNotEmpty()) {
            transfers.byCircleTx(execution.walletId, execution.circleTxId)?.let { return Resource.success(it) }
        }
        if (execution.txHash.isEmpty()) return Resource.success(null)
        val now = clock.instant()
        val candidates =
            transfers.unsettledSplits(
                execution.walletId,
                createdAfter = now.minus(MATCH_WINDOW),
                createdBefore = now.plusSeconds(1),
                limit = CANDIDATES,
            )
        if (candidates.isEmpty()) return Resource.success(null)

        val records =
            when (val read = custody.chainTransaction(execution.blockchain, execution.txHash)) {
                is Resource.Success -> read.data
                is Resource.Error -> return read
            }
        val sender = Addresses.normalise(execution.sourceAddress)
        val legs =
            records
                .filter { it.direction == CustodyDirection.IN && it.operation == CustodyOperation.TRANSFER }
                .filter { sender.isEmpty() || Addresses.normalise(it.sourceAddress) == sender }
                .map { Leg(Addresses.normalise(it.destinationAddress), it.amount.asset.symbol, it.amount.amountMinor) }
        val matching = candidates.filter { it.isPaidBy(legs) }
        if (matching.size > 1) warnings.splitPaymentAmbiguous(execution.circleTxId, matching.size)
        return Resource.success(matching.maxByOrNull { it.createdAt })
    }

    private fun Transfer.isPaidBy(legs: List<Leg>): Boolean {
        val feeTo = feeAddress?.let(Addresses::normalise) ?: return false
        val coin = amount.asset.symbol
        if (Leg(feeTo, coin, feeMinor) !in legs) return false
        val payee = Addresses.normalise(destinationAddress)
        return Leg(payee, coin, amount.amountMinor) in legs || legs.none { it.address == payee }
    }

    /** One leg as it landed: where, in which coin (2026-11-07: any coin), how much. */
    private data class Leg(val address: String, val symbol: String, val amountMinor: BigInteger)

    private companion object {
        /** How far back a batch's transfer may have been prepared: as far as `wallet.unsettled-splits` looks. */
        val MATCH_WINDOW: Duration = Duration.ofDays(3)

        /** The most unsettled split payments one wallet is expected to have. */
        const val CANDIDATES = 50
    }
}
