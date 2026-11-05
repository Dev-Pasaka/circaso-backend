package com.circaso.wallet.domain.support

import com.circaso.messaging.api.ChatDirectory
import com.circaso.shared.UserId
import com.circaso.wallet.api.SpaceWalletPolicy
import com.circaso.wallet.domain.models.WalletOwner
import com.circaso.wallet.domain.ports.FrameSender
import com.circaso.wallet.domain.ports.WalletFrame

/**
 * Frames are addressed to a wallet's holder; this sends each to the people behind it (decision 2026-11-03). A
 * person's own wallet: to them, as before. A group wallet: `transfer.updated` and `payment.received` to its signer
 * (the one who sends from it and is pushed about it), `wallet.ready` to every member (the group's info turns from
 * "being set up" to the wallet), each with the wallet's id so the phone knows it isn't the personal wallet. Members
 * hear of contributions and payouts from chama's own frames.
 */
class HolderFrames(
    private val holders: Holders,
    private val chats: ChatDirectory,
    private val sender: FrameSender,
    private val spaces: SpaceWalletPolicy? = null,
) : FrameSender {
    override suspend fun send(userId: UserId, frame: WalletFrame) {
        val owner = holders.owner(userId)
        if (owner is WalletOwner.Space) {
            space(owner, frame)
            return
        }
        val group = owner as? WalletOwner.Group
        if (group == null) {
            sender.send(userId, frame)
            return
        }
        val walletId = group.walletId
        when (frame) {
            is WalletFrame.TransferUpdated -> {
                sender.send(group.signerId, frame.copy(walletId = walletId))
            }

            is WalletFrame.PaymentReceived -> {
                sender.send(group.signerId, frame.copy(walletId = walletId, ownerType = "GROUP"))
            }

            is WalletFrame.WalletReady -> {
                chats.members(group.chatId).forEach { sender.send(it, frame.copy(walletId = walletId)) }
            }

            is WalletFrame.SetupRequested -> {
                Unit
            }
        }
    }

    /**
     * A space wallet (Spaces phase 3): its money is the owner's to follow (`transfer.updated`, `payment.received`);
     * `wallet.ready` goes to the owner and the space's admins, who see the wallet too.
     */
    private suspend fun space(owner: WalletOwner.Space, frame: WalletFrame) {
        val walletId = owner.walletId
        when (frame) {
            is WalletFrame.TransferUpdated -> {
                sender.send(owner.signerId, frame.copy(walletId = walletId))
            }

            is WalletFrame.PaymentReceived -> {
                val named =
                    frame.copy(
                        walletId = walletId,
                        ownerType = "SPACE",
                        spaceName = spaces?.name(owner.spaceId),
                    )
                sender.send(owner.signerId, named)
            }

            is WalletFrame.WalletReady -> {
                val team = spaces?.team(owner.spaceId).orEmpty() + owner.signerId
                team.forEach { sender.send(it, frame.copy(walletId = walletId)) }
            }

            is WalletFrame.SetupRequested -> {
                Unit
            }
        }
    }
}
