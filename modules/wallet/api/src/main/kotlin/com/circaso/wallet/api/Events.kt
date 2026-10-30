package com.circaso.wallet.api

import com.circaso.messaging.api.ChatId
import com.circaso.shared.DomainEvent
import com.circaso.shared.UserId
import kotlinx.serialization.Serializable

// Published on `events:wallet`.

/**
 * A wallet came to be on [blockchain]: the user's first one when the wallet turns `ACTIVE`, and one more each time
 * they add a chain (decision 2026-10-28, a wallet on several chains). Published once per chain.
 */
@Serializable
data class WalletCreated(val userId: UserId, val blockchain: String) : DomainEvent {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "wallet.WalletCreated"
    }
}

/**
 * A transfer the wallet prepared reached the chain (Circle `CONFIRMED` or `COMPLETE`). Published once per transfer,
 * by the update that moved it there. [proposalId] is set when Asha proposed it (Phase B).
 */
@Serializable
data class TransferConfirmed(
    val transferId: TransferId,
    val senderId: UserId,
    val recipientId: UserId? = null,
    val blockchain: String,
    val amount: WalletAmount,
    /** `APP` or `ASHA`. */
    val origin: String,
    val proposalId: String? = null,
    /** The chat it was paid from, when it was (added 2026-10-29 for chama contributions). */
    val chatId: ChatId? = null,
) : DomainEvent {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "wallet.TransferConfirmed"
    }
}

/** A transfer the wallet prepared failed (Circle `FAILED`, `DENIED` or `CANCELLED`); published once per transfer. */
@Serializable
data class TransferFailed(
    val transferId: TransferId,
    val senderId: UserId,
    /** Circle's reason as it gave it, or `FAILED` when it gave none. */
    val reason: String,
) : DomainEvent {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "wallet.TransferFailed"
    }
}
