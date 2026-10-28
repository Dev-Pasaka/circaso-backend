package com.circaso.wallet.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.Transfer
import com.circaso.wallet.domain.repositories.TransferRepository

/**
 * W5 `GET /wallet/transfers/{transferId}`: one transfer and its state, for its sender and for the person it pays (the
 * payment card in a chat carries the id). Anyone else gets `wallet.transfer_not_found`, as for one that doesn't exist.
 */
class GetTransfer(private val transfers: TransferRepository) {
    suspend operator fun invoke(userId: UserId, transferId: TransferId): Resource<Transfer> {
        val transfer = transfers.find(transferId) ?: return Resource.error(WalletError.TransferNotFound)
        if (transfer.senderId != userId && transfer.recipientId != userId) {
            return Resource.error(WalletError.TransferNotFound)
        }
        return Resource.success(transfer)
    }
}
