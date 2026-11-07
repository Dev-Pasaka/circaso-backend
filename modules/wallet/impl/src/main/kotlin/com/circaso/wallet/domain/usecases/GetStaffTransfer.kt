package com.circaso.wallet.domain.usecases

import com.circaso.shared.Resource
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.domain.models.Transfer
import com.circaso.wallet.domain.repositories.TransferRepository

/** Staff: one transfer by its id, whoever sent it (decision 2026-11-08). */
class GetStaffTransfer(private val transfers: TransferRepository) {
    suspend operator fun invoke(transferId: TransferId): Resource<Transfer?> =
        Resource.success(transfers.find(transferId))
}
