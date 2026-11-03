package com.circaso.wallet.domain.support

import com.circaso.messaging.api.ChatDirectory
import com.circaso.shared.UserId
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
class HolderFrames(private val holders: Holders, private val chats: ChatDirectory, private val sender: FrameSender) :
    FrameSender {
    override suspend fun send(userId: UserId, frame: WalletFrame) {
        val group = holders.owner(userId) as? WalletOwner.Group
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
                sender.send(group.signerId, frame.copy(walletId = walletId))
            }

            is WalletFrame.WalletReady -> {
                chats.members(group.chatId).forEach { sender.send(it, frame.copy(walletId = walletId)) }
            }

            is WalletFrame.SetupRequested -> {
                Unit
            }
        }
    }
}
