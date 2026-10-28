package com.circaso.wallet.presentation.services

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.domain.models.AddressCheck
import com.circaso.wallet.domain.models.PreparedTransfer
import com.circaso.wallet.domain.models.Transfer
import com.circaso.wallet.domain.models.TransferCommand
import com.circaso.wallet.domain.usecases.GetTransfer
import com.circaso.wallet.domain.usecases.PrepareTransfer
import com.circaso.wallet.domain.usecases.ValidateAddress

/** Sending: W4 prepare, W5 one transfer, W11 checking an outside address. */
class TransferService(
    private val prepareTransfer: PrepareTransfer,
    private val getTransfer: GetTransfer,
    private val validateAddress: ValidateAddress,
) {
    suspend fun prepare(command: TransferCommand): Resource<PreparedTransfer> = prepareTransfer(command)

    suspend fun transfer(userId: UserId, transferId: TransferId): Resource<Transfer> = getTransfer(userId, transferId)

    suspend fun validate(blockchain: String, address: String): Resource<AddressCheck> =
        validateAddress(blockchain, address)
}
