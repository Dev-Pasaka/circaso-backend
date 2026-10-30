package com.circaso.chama.domain.models

import com.circaso.chama.api.RequestId
import com.circaso.messaging.api.ChatId
import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferId
import java.time.Instant

/**
 * A chama's goal (decision 2026-10-29): a group chat saves towards a target, and the money sits in the treasurer's
 * own wallet. Contributions are ordinary USDC payments from members to the treasurer, paid from the chat; paying out
 * needs the admins' approval, then the treasurer's PIN. No group-owned key exists anywhere.
 */
data class Goal(
    val chatId: ChatId,
    val name: String,
    /** USDC minor units. */
    val targetMinor: Long,
    val deadline: Instant?,
    val treasurerId: UserId,
    val createdBy: UserId,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

/** A member's confirmed payment to the treasurer from the chat; one per transfer. */
data class Contribution(
    val transferId: TransferId,
    val chatId: ChatId,
    val userId: UserId,
    val amountMinor: Long,
    val at: Instant,
)

enum class RequestState {
    PENDING,
    APPROVED,
    DECLINED,
    PAID,
    EXPIRED,
}

enum class Decision {
    APPROVE,
    DECLINE,
}

data class Approval(val userId: UserId, val decision: Decision, val at: Instant)

/**
 * A member's request to pay someone from the group: approved by the admins (two, or all of them when fewer), then
 * paid by the treasurer with their own transfer (W4 from the chat), which [transferId] links.
 */
data class Payout(
    val requestId: RequestId,
    val chatId: ChatId,
    val requestedBy: UserId,
    val payeeId: UserId?,
    val payeeAddress: String?,
    /** What the request calls the payee ("Coast Bus"), for an outside address. */
    val payeeName: String?,
    val amountMinor: Long,
    val note: String?,
    val state: RequestState,
    val approvalsNeeded: Int,
    val approvals: List<Approval>,
    val transferId: TransferId?,
    val expiresAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val approvedBy: List<UserId> get() = approvals.filter { it.decision == Decision.APPROVE }.map { it.userId }
    val declinedBy: List<UserId> get() = approvals.filter { it.decision == Decision.DECLINE }.map { it.userId }
    val isOpen get() = state == RequestState.PENDING || state == RequestState.APPROVED
}

/** The goal as the screens show it (D2's header, D4). */
data class ChamaView(
    val goal: Goal,
    val memberCount: Int,
    val contributedMinor: Long,
    val paidOutMinor: Long,
    /** What the treasurer still holds for the group: contributed minus paid out. */
    val heldMinor: Long,
    val myContributedMinor: Long,
    val contributorsMinor: Map<UserId, Long>,
    val openRequests: List<Payout>,
    val isAdmin: Boolean,
    val isTreasurer: Boolean,
)

/** One line of the ledger (D4): a contribution in, or a request paid out. */
sealed interface LedgerEntry {
    val at: Instant
    val amountMinor: Long

    data class In(val contribution: Contribution) : LedgerEntry {
        override val at get() = contribution.at
        override val amountMinor get() = contribution.amountMinor
    }

    data class Out(val request: Payout) : LedgerEntry {
        override val at get() = request.updatedAt
        override val amountMinor get() = request.amountMinor
    }
}

data class SetGoalCommand(
    val userId: UserId,
    val chatId: ChatId,
    val name: String,
    val targetMinor: Long,
    val deadline: Instant?,
    val treasurerId: UserId?,
)

data class AskPayoutCommand(
    val userId: UserId,
    val chatId: ChatId,
    val payeeId: UserId?,
    val payeeAddress: String?,
    val payeeName: String?,
    val amountMinor: Long,
    val note: String?,
)
