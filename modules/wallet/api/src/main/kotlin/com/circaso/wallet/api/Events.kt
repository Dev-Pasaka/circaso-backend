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
data class WalletCreated(
    /** The wallet's holder: the person, or a group wallet's id ([groupId] says which group). */
    val userId: UserId,
    val blockchain: String,
    /** Set for a group wallet (added 2026-11-03). */
    val groupId: ChatId? = null,
    /** Set for a space wallet: spaces' id (added 2026-11-04, Spaces phase 3). */
    val spaceId: String? = null,
) : DomainEvent {
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
    /** Set when it left a group wallet; [senderId] is then that wallet's id (added 2026-11-03). */
    val fromGroupId: ChatId? = null,
    /** Set when it paid a group wallet; [recipientId] is then that wallet's id: a contribution. */
    val toGroupId: ChatId? = null,
    /** The approved payout request it paid, for a payment from a group wallet. */
    val payoutRequestId: String? = null,
    /** The person who signed it. */
    val signerId: UserId? = null,
    /** Set when it left a space wallet; [senderId] is then that wallet's id (added 2026-11-04, Spaces phase 3). */
    val fromSpaceId: String? = null,
    /** Set when it paid a space wallet; [recipientId] is then that wallet's id. */
    val toSpaceId: String? = null,
    /** The membership payment (SP13) it paid, when it was one. */
    val spacePaymentId: String? = null,
    /**
     * What [amount] was worth in US dollars, in micro-dollars (6 decimals), at the price the payment was priced at
     * (added 2026-11-07, when any held coin can be paid): the same digits as `amount.amountMinor` for USDC and USDT.
     * Null when it can't be said (a coin whose price wasn't kept).
     */
    val usdValueMinor: String? = null,
    /** [amount]'s coin's decimals (added 2026-11-07). */
    val assetDecimals: Int? = null,
) : DomainEvent {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "wallet.TransferConfirmed"
    }
}

/**
 * A payment into a group's wallet was signed and is on its way (Circle accepted it; the transfer moved to `SUBMITTED`),
 * added 2026-11-09 so every member of the group hears it the moment it is sent rather than when the chain confirms it
 * (a group call shows it landing live). Published once per transfer, only for a transfer to a group wallet
 * ([toGroupId] set); [TransferConfirmed] still follows when the chain confirms it, or [TransferFailed].
 */
@Serializable
data class TransferSubmitted(
    val transferId: TransferId,
    /** The member who paid. */
    val senderId: UserId,
    /** The group whose wallet it pays. */
    val toGroupId: ChatId,
    val blockchain: String,
    val amount: WalletAmount,
    /** The chat it was paid from, when it was. */
    val chatId: ChatId? = null,
    /** As [TransferConfirmed.usdValueMinor]: its value in micro-dollars, null when the coin's price wasn't kept. */
    val usdValueMinor: String? = null,
    /** [amount]'s coin's decimals. */
    val assetDecimals: Int? = null,
) : DomainEvent {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "wallet.TransferSubmitted"
    }
}

/** A transfer the wallet prepared failed (Circle `FAILED`, `DENIED` or `CANCELLED`); published once per transfer. */
@Serializable
data class TransferFailed(
    val transferId: TransferId,
    val senderId: UserId,
    /** Circle's reason as it gave it, or `FAILED` when it gave none. */
    val reason: String,
    /** Set when it was to leave a group wallet (added 2026-11-03). */
    val fromGroupId: ChatId? = null,
    /** The approved payout request it was to pay; the request stays `APPROVED` and can be paid again. */
    val payoutRequestId: String? = null,
    /** The membership payment (SP13) it was to pay (added 2026-11-04). */
    val spacePaymentId: String? = null,
) : DomainEvent {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "wallet.TransferFailed"
    }
}
