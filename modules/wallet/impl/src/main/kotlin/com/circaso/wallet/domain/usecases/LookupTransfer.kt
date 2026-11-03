package com.circaso.wallet.domain.usecases

import com.circaso.wallet.api.TransferId
import com.circaso.wallet.api.TransferSummary
import com.circaso.wallet.domain.repositories.TransferRepository
import com.circaso.wallet.domain.support.Amounts

/** `Wallets.transfer`, for another module (chama links a group's payment to the transfer that paid it). */
class LookupTransfer(private val transfers: TransferRepository) {
    suspend operator fun invoke(transferId: TransferId): TransferSummary? =
        transfers.find(transferId)?.let {
            TransferSummary(
                transferId = it.transferId,
                senderId = it.senderId,
                recipientId = it.recipientId,
                destinationAddress = it.destinationAddress,
                amount = Amounts.of(it.amount),
                state = it.state.name,
                chatId = it.chatId,
                fromGroupId = it.fromGroupId,
                toGroupId = it.toGroupId,
                signerId = it.signer,
                payoutRequestId = it.payoutRequestId,
            )
        }
}
