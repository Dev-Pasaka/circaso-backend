package com.circaso.wallet.api

import com.circaso.messaging.api.ChatId
import com.circaso.shared.UserId
import java.util.UUID

/** What other modules may ask the wallet. The assistant reads it later, for Asha's payment proposals (Phase B). */
interface Wallets {
    /** Whether [userId] has a wallet that is set up on at least one chain. */
    suspend fun hasWallet(userId: UserId): Boolean

    /** One transfer as another module may see it (chama links a group's payment to it); null when there is none. */
    suspend fun transfer(transferId: TransferId): TransferSummary?

    /**
     * The group's own wallet (decision 2026-11-03, Spaces phase 2), for chama: a target and a payout need one. Null
     * when the group never set one up.
     */
    suspend fun groupWallet(chatId: ChatId): GroupWalletSummary?
}

/** A transfer's facts for another module: who sent what to whom, from which chat, and where it stands. */
data class TransferSummary(
    val transferId: TransferId,
    /** The wallet it left: a person's id, or a group wallet's id ([fromGroupId] says which group). */
    val senderId: UserId,
    val recipientId: UserId?,
    val destinationAddress: String,
    val amount: WalletAmount,
    /** `AWAITING_SIGNATURE`, `SUBMITTED`, `CONFIRMED`, `FAILED` or `EXPIRED`. */
    val state: String,
    val chatId: ChatId?,
    /** Set when it left a group wallet (added 2026-11-03). */
    val fromGroupId: ChatId? = null,
    /** Set when it paid a group wallet. */
    val toGroupId: ChatId? = null,
    /** The person who signed it: the sender for a personal wallet, the group wallet's owner for a group's. */
    val signerId: UserId? = null,
    /** The approved payout request it pays, for a group wallet (W4 `payoutRequestId`). */
    val payoutRequestId: UUID? = null,
)

/**
 * A group's wallet as chama needs it. [status] is `SETTING_UP` until the owner has set its PIN and Circle has made it,
 * then `ACTIVE`. [signerId] is the one person who can send from it (option A, decision 2026-11-03): whoever set the
 * PIN, after the admins approve.
 */
data class GroupWalletSummary(
    val walletId: WalletId,
    val chatId: ChatId,
    val status: String,
    val signerId: UserId,
) {
    val isActive: Boolean get() = status == "ACTIVE"
}

/**
 * Asked by the wallet before it prepares a payment from a group wallet (W4): money leaves a group wallet only for a
 * payout request the group's admins approved (option A). Chama implements it and its Koin module binds it, as
 * identity's `DeletionGuard` is implemented by wallet; the wallet's api can't name chama's request id type (chama's
 * api depends on this one), so it is a UUID.
 */
interface PayoutApprovals {
    /** The request [requestId] in [chatId] when it is `APPROVED` and not yet paid; null otherwise. */
    suspend fun approved(chatId: ChatId, requestId: UUID): ApprovedPayout?
}

/** What the admins approved: pay exactly this to exactly this payee. Never logged. */
data class ApprovedPayout(
    val requestId: UUID,
    val chatId: ChatId,
    val payeeUserId: UserId?,
    val payeeAddress: String?,
    /** USDC minor units. */
    val amountMinor: Long,
) {
    override fun toString(): String = "ApprovedPayout($requestId)"
}
