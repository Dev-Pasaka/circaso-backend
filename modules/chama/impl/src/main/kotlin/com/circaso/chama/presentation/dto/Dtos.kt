package com.circaso.chama.presentation.dto

import com.circaso.wallet.api.WalletAmount
import kotlinx.serialization.Serializable

// Amounts are strings, as across the wallet (decision 2026-10-28).

/** `PUT /chama/chats/{chatId}/goal`. */
@Serializable
data class SetGoalRequest(
    val name: String,
    /** `amountMinor` (digits) or `amount` (whole USDC, at most 6 decimals); both only if they agree. */
    val target: AmountRequest,
    /** ISO-8601, optional. */
    val deadline: String? = null,
    /** Retired with the treasurer model (decision 2026-11-03): ignored; the money goes to the group's wallet. */
    val treasurerUserId: String? = null,
)

@Serializable
data class AmountRequest(val asset: String = "USDC", val amountMinor: String? = null, val amount: String? = null)

@Serializable
data class GoalDto(
    val chatId: String,
    val name: String,
    val target: WalletAmount,
    val deadline: String? = null,
    /** Who sends from the group wallet (its signer); the treasurer on goals from before group wallets. */
    val treasurerUserId: String? = null,
    val createdBy: String,
    val updatedAt: String,
    val version: Long,
)

@Serializable
data class ContributorDto(val userId: String, val contributed: WalletAmount)

/** `GET /chama/chats/{chatId}`. */
@Serializable
data class ChamaResponse(
    val goal: GoalDto,
    val memberCount: Int,
    val contributed: WalletAmount,
    val paidOut: WalletAmount,
    val held: WalletAmount,
    val myContribution: WalletAmount,
    val contributors: List<ContributorDto>,
    val openRequests: List<RequestDto>,
    val isAdmin: Boolean,
    /** Whether you send from the group wallet (its signer). */
    val isTreasurer: Boolean,
    /** The group wallet's id (W1 `?walletId=`), null before it is set up (added 2026-11-03). */
    val walletId: String? = null,
    /** `TWO_ADMINS` or `ANY_ADMIN`. */
    val approvalPolicy: String = "TWO_ADMINS",
)

/** CH7 `GET /chama/chats/{chatId}/money`: a group's money, target or not (added 2026-11-03). */
@Serializable
data class GroupMoneyResponse(
    val chatId: String,
    /** The group's wallet; null before the owner sets one up (no money actions in the group then). */
    val wallet: GroupWalletDto? = null,
    /** `TWO_ADMINS` (every admin when there are fewer) or `ANY_ADMIN`. */
    val approvalPolicy: String,
    /** The target; null when the group has none. */
    val goal: GoalDto? = null,
    val memberCount: Int,
    /** Towards the target, since it was set; zero without one. */
    val contributed: WalletAmount,
    val myContribution: WalletAmount,
    val contributors: List<ContributorDto>,
    val paidOut: WalletAmount,
    val openRequests: List<RequestDto>,
    val isAdmin: Boolean,
    /** The group's owner: chooses who approves payouts (CH9). */
    val isOwner: Boolean,
    /** You send from the group wallet: you pay approved requests (W4 with `walletId` and `payoutRequestId`). */
    val isSigner: Boolean,
)

@Serializable
data class GroupWalletDto(
    val walletId: String,
    /** `SETTING_UP` or `ACTIVE`. */
    val status: String,
    val signerUserId: String,
)

/** CH9 `PUT /chama/chats/{chatId}/settings`. */
@Serializable
data class GroupSettingsRequest(
    /** `TWO_ADMINS` or `ANY_ADMIN`. */
    val approvalPolicy: String,
)

@Serializable
data class GroupSettingsDto(val chatId: String, val approvalPolicy: String, val updatedAt: String? = null)

@Serializable
data class LedgerEntryDto(
    /** `IN` (a contribution) or `OUT` (a paid request). */
    val direction: String,
    val at: String,
    val amount: WalletAmount,
    val userId: String? = null,
    val transferId: String? = null,
    val request: RequestDto? = null,
)

@Serializable
data class LedgerResponse(val entries: List<LedgerEntryDto>, val next: String? = null)

/** `POST /chama/chats/{chatId}/requests`. */
@Serializable
data class CreateRequestRequest(val to: PayeeRequest, val amount: AmountRequest, val note: String? = null)

/** Who to pay: a Circaso user, or an address (with a name for the ledger). Exactly one. */
@Serializable
data class PayeeRequest(val userId: String? = null, val address: String? = null, val name: String? = null)

@Serializable
data class ApprovalDto(val userId: String, val decision: String, val at: String)

@Serializable
data class RequestDto(
    val requestId: String,
    val chatId: String,
    val requestedBy: String,
    val payeeUserId: String? = null,
    val payeeAddress: String? = null,
    val payeeName: String? = null,
    val amount: WalletAmount,
    val note: String? = null,
    /** `PENDING`, `APPROVED`, `DECLINED`, `PAID`, `EXPIRED`. */
    val state: String,
    val approvalsNeeded: Int,
    val approvals: List<ApprovalDto>,
    val transferId: String? = null,
    val expiresAt: String,
    val createdAt: String,
    val updatedAt: String,
)

/** `POST /chama/requests/{requestId}/payment`. */
@Serializable
data class AttachPaymentRequest(val transferId: String)
