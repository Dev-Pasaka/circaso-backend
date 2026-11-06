package com.circaso.wallet.api

import com.circaso.messaging.api.ChatId
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Instant
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

    /**
     * The space's own wallet (Spaces phase 3), for spaces: a space goes live once it is `ACTIVE`. Null when the owner
     * never started one. [spaceId] is spaces' id (spaces' api depends on nothing, so it is a UUID here).
     */
    suspend fun spaceWallet(spaceId: UUID): SpaceWalletSummary? = null

    /**
     * Whether the space's wallet still holds money (read from Circle, as the deletion guard does), for spaces: a space
     * is deleted only once its wallet is empty. An error when Circle can't say; false when it has no wallet.
     */
    suspend fun spaceWalletHoldsMoney(spaceId: UUID): Resource<Boolean> = Resource.success(false)

    /**
     * The address [userId]'s own wallet is paid at on [blockchain], for share links (decision 2026-11-06: an address
     * page is built from the wallet's record, never from what a phone sent). Null when the wallet isn't on that chain.
     */
    suspend fun depositAddress(userId: UserId, blockchain: String): String? = null
}

/** A space's wallet as spaces needs it: `SETTING_UP` until its owner's PIN and Circle made it, then `ACTIVE`. */
data class SpaceWalletSummary(
    val walletId: WalletId,
    val spaceId: UUID,
    val status: String,
    val signerId: UserId,
    /** The chains it can be paid on now. */
    val blockchains: List<String> = emptyList(),
) {
    val isActive: Boolean get() = status == "ACTIVE"
}

/**
 * Asked by the wallet about a space's wallet (Spaces phase 3): who may see it, sign for it and pay it, and what a
 * membership costs. Spaces implements it and its Koin module binds it, as chama does [PayoutApprovals]; spaces' ids
 * are UUIDs here, since spaces' api can't be named by this one.
 */
interface SpaceWalletPolicy {
    /** What [userId] is to the space: its owner, an admin (sees the wallet), a member (may pay it) or nothing. */
    suspend fun access(spaceId: UUID, userId: UserId): SpaceAccess

    /** The spaces whose wallet [userId] may see without signing for it (they are an admin), for W16. */
    suspend fun administered(userId: UserId): List<UUID>

    /** The space's owner and admins: who hears that its wallet is ready. */
    suspend fun team(spaceId: UUID): Set<UserId>

    /** The space's name, for the push that money arrived in its wallet; null when there is no such space. */
    suspend fun name(spaceId: UUID): String? = null

    /** The membership payment [paymentId] made for [payer] (SP13), while it can still be paid; null otherwise. */
    suspend fun membershipCharge(paymentId: UUID, payer: UserId): MembershipCharge?
}

/**
 * [MEMBER] only while the space is live and the person is in it with a membership that hasn't ended, not banned;
 * [OWNER] and [ADMIN] whatever the space's state (its wallet is set up before it goes live).
 */
enum class SpaceAccess { OWNER, ADMIN, MEMBER, NONE }

/** What a membership payment must be: exactly this, into this space's wallet. Never logged. */
data class MembershipCharge(val paymentId: UUID, val spaceId: UUID, val amountMinor: Long) {
    override fun toString(): String = "MembershipCharge($paymentId)"
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
    /** The chain it moves on (added 2026-11-06, for receipts). */
    val blockchain: String? = null,
    /** The on-chain hash once Circle reports one. */
    val txHash: String? = null,
    val createdAt: Instant? = null,
    /** When its state last changed: for a `CONFIRMED` or `FAILED` transfer, when it settled. */
    val updatedAt: Instant? = null,
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
