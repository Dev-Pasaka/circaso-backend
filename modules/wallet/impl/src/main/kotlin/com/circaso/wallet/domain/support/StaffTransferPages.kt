package com.circaso.wallet.domain.support

import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.wallet.domain.models.Transfer
import com.circaso.wallet.domain.models.TransferKey
import com.circaso.wallet.domain.models.TransferParty
import com.circaso.wallet.domain.models.TransferQuery
import com.circaso.wallet.domain.models.TransferResults
import com.circaso.wallet.domain.repositories.WalletReportRepository
import java.util.UUID

/**
 * A newest-first keyset page of transfers for staff (decision 2026-11-08). A person's transfers, sent or received, are
 * two reads, one on each side's index (`transfers_sender_idx`, `transfers_recipient_idx`), merged: an `OR` across the
 * two columns could use neither index for the order and would sort every transfer the person ever made. Each side
 * reads one more than the page past the cursor, so the merge's first rows are exactly the page.
 */
class StaffTransferPages(private val reports: WalletReportRepository, private val feeWallets: FeeWallets? = null) {
    suspend fun page(
        query: TransferQuery,
        person: UserId?,
        cursor: String?,
        limit: Int,
    ): Resource<TransferResults> {
        val after =
            cursor?.let {
                StaffCursors.transfer(it) ?: return Resource.error(SharedError.Malformed("cursor", "invalid"))
            }
        val page = limit.coerceIn(1, MAX_PAGE)
        val found =
            if (person == null) {
                reports.transfers(query, after, page + 1)
            } else {
                merged(query, person, after, page + 1)
            }
        val shown = found.take(page)
        val next =
            if (found.size >
                page
            ) {
                shown.last().let { StaffCursors.encode(TransferKey(it.createdAt, it.transferId)) }
            } else {
                null
            }
        val toFeeWallet =
            feeWallets
                ?.let { wallets ->
                    shown
                        .filter { it.recipientId == null && wallets.isFeeWallet(it.blockchain, it.destinationAddress) }
                        .map { it.transferId }
                        .toSet()
                }.orEmpty()
        return Resource.success(TransferResults(shown, next, toFeeWallet))
    }

    private suspend fun merged(
        query: TransferQuery,
        person: UserId,
        after: TransferKey?,
        limit: Int,
    ): List<Transfer> {
        val sent = reports.transfers(query.copy(party = TransferParty.Sender(person)), after, limit)
        val received = reports.transfers(query.copy(party = TransferParty.Recipient(person)), after, limit)
        // A payment to oneself (between one's own chains) is on both sides; it is listed once.
        return (sent + received)
            .distinctBy { it.transferId }
            .sortedWith(NEWEST_FIRST)
            .take(limit)
    }

    private companion object {
        const val MAX_PAGE = 200

        private val UNSIGNED =
            Comparator<UUID> { a, b ->
                val high = a.mostSignificantBits.toULong().compareTo(b.mostSignificantBits.toULong())
                if (high != 0) high else a.leastSignificantBits.toULong().compareTo(b.leastSignificantBits.toULong())
            }

        /** `(created_at, transfer_id) DESC`, the ids compared unsigned as PostgreSQL compares uuids. */
        val NEWEST_FIRST: Comparator<Transfer> =
            compareByDescending<Transfer> { it.createdAt }.thenByDescending(UNSIGNED) { it.transferId.value }
    }
}
