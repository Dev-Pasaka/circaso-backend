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
    /** Whose wallet holds the money; the caller when left out. Must be a member. */
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
    val treasurerUserId: String,
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
    val isTreasurer: Boolean,
)

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
