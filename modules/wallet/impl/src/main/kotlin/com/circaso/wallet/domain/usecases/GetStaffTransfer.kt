package com.circaso.wallet.domain.usecases

import com.circaso.shared.Resource
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.domain.models.StaffTransfer
import com.circaso.wallet.domain.repositories.TransferRepository
import com.circaso.wallet.domain.support.FeeWallets

/** Staff: one transfer by its id, whoever sent it (decision 2026-11-08), and whether it went to Circaso's fee wallet. */
class GetStaffTransfer(private val transfers: TransferRepository, private val feeWallets: FeeWallets? = null) {
    suspend operator fun invoke(transferId: TransferId): Resource<StaffTransfer?> {
        val transfer = transfers.find(transferId) ?: return Resource.success(null)
        val toFeeWallet =
            transfer.recipientId == null &&
                feeWallets?.isFeeWallet(transfer.blockchain, transfer.destinationAddress) == true
        return Resource.success(StaffTransfer(transfer, toFeeWallet))
    }
}
