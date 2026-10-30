package com.circaso.chama.api

import com.circaso.messaging.api.ChatId
import com.circaso.shared.DomainEvent
import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.api.WalletAmount
import kotlinx.serialization.Serializable

// Published on `events:chama`.

/** A member's payment to the treasurer reached the chain and was counted towards the goal. Once per transfer. */
@Serializable
data class ContributionRecorded(
    val chatId: ChatId,
    val userId: UserId,
    val transferId: TransferId,
    val amount: WalletAmount,
) : DomainEvent {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "chama.ContributionRecorded"
    }
}

/** A group's approved payment was paid by the treasurer and reached the chain. Once per request. */
@Serializable
data class RequestPaid(
    val chatId: ChatId,
    val requestId: RequestId,
    val transferId: TransferId,
    val amount: WalletAmount,
) : DomainEvent {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "chama.RequestPaid"
    }
}
