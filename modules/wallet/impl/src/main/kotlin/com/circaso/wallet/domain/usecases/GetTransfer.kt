package com.circaso.wallet.domain.usecases

import com.circaso.messaging.api.ChatDirectory
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.api.SpaceAccess
import com.circaso.wallet.api.SpaceWalletPolicy
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.SeenTransfer
import com.circaso.wallet.domain.repositories.TransferRepository

/**
 * W5 `GET /wallet/transfers/{transferId}`: one transfer and its state, for its sender and for the person it pays (the
 * payment card in a chat carries the id), and, when a group wallet is on either side, for every member of that group
 * (decision 2026-11-03: everyone sees every payment). Anyone else gets `wallet.transfer_not_found`, as for one that
 * doesn't exist.
 */
class GetTransfer(
    private val transfers: TransferRepository,
    private val chats: ChatDirectory,
    /** Spaces' guard (Spaces phase 3): a space wallet's payments are seen by its owner and admins. */
    private val spaces: SpaceWalletPolicy? = null,
) {
    suspend operator fun invoke(userId: UserId, transferId: TransferId): Resource<SeenTransfer> {
        val transfer = transfers.find(transferId) ?: return Resource.error(WalletError.TransferNotFound)
        val recipient = transfer.recipientId
        val side =
            when {
                transfer.senderId == userId -> transfer.senderId
                recipient == userId -> recipient
                transfer.fromGroupId?.let { chats.isMember(it, userId) } == true -> transfer.senderId
                recipient != null && transfer.toGroupId?.let { chats.isMember(it, userId) } == true -> recipient
                transfer.fromSpaceId?.let { seesSpaceWallet(it, userId) } == true -> transfer.senderId
                recipient != null && transfer.toSpaceId?.let { seesSpaceWallet(it, userId) } == true -> recipient
                else -> return Resource.error(WalletError.TransferNotFound)
            }
        return Resource.success(SeenTransfer(transfer, side))
    }

    private suspend fun seesSpaceWallet(spaceId: java.util.UUID, userId: UserId): Boolean {
        val access = spaces?.access(spaceId, userId) ?: return false
        return access == SpaceAccess.OWNER || access == SpaceAccess.ADMIN
    }
}
