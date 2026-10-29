package com.circaso.wallet.api

import com.circaso.messaging.api.ChatId
import com.circaso.shared.UserId

/** What other modules may ask the wallet. The assistant reads it later, for Asha's payment proposals (Phase B). */
interface Wallets {
    /** Whether [userId] has a wallet that is set up on at least one chain. */
    suspend fun hasWallet(userId: UserId): Boolean

    /** One transfer as another module may see it (chama links a group's payment to it); null when there is none. */
    suspend fun transfer(transferId: TransferId): TransferSummary?
}

/** A transfer's facts for another module: who sent what to whom, from which chat, and where it stands. */
data class TransferSummary(
    val transferId: TransferId,
    val senderId: UserId,
    val recipientId: UserId?,
    val destinationAddress: String,
    val amount: WalletAmount,
    /** `AWAITING_SIGNATURE`, `SUBMITTED`, `CONFIRMED`, `FAILED` or `EXPIRED`. */
    val state: String,
    val chatId: ChatId?,
)
