package com.circaso.chama.domain.models

import com.circaso.chama.api.RequestId
import com.circaso.messaging.api.ChatId
import com.circaso.shared.UserId
import com.circaso.wallet.api.GroupWalletSummary
import com.circaso.wallet.api.TransferId
import java.time.Instant

/**
 * A group's target (decision 2026-11-03, Spaces phase 2; it was the chama's goal): optional, on a group with a wallet.
 * The money sits in the group's own wallet; contributions are members' payments into it (W4 `to: { groupId }`),
 * counted towards the target from when it was set ([createdAt]). Paying out needs the admins' approval, then the
 * wallet signer's PIN. [treasurerId] is only on goals from the treasurer model (decision 2026-10-29), now retired.
 */
data class Goal(
    val chatId: ChatId,
    val name: String,
    /** USDC minor units. */
    val targetMinor: Long,
    val deadline: Instant?,
    val treasurerId: UserId?,
    val createdBy: UserId,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

/**
 * A member's confirmed payment into the group's wallet; one per transfer. [legacy] marks one paid to a treasurer
 * before group wallets: in the ledger, never counted towards a target.
 */
data class Contribution(
    val transferId: TransferId,
    val chatId: ChatId,
    val userId: UserId,
    /** In US dollars, micro-dollars (USDC minor units): what the group's goal counts, whatever coin was paid. */
    val amountMinor: Long,
    val at: Instant,
    val legacy: Boolean = false,
    /** The coin that was actually paid (2026-11-07: any held coin); null on contributions recorded before. */
    val paid: PaidCoin? = null,
)

/** A payment the group wallet's owner sent straight from it, with no request (decision 2026-11-06). */
data class DirectPayout(
    val transferId: TransferId,
    val chatId: ChatId,
    /** In US dollars, micro-dollars, as [Contribution.amountMinor]. */
    val amountMinor: Long,
    val at: Instant,
    /** The coin that was actually sent; null on payouts recorded before 2026-11-07. */
    val paid: PaidCoin? = null,
)

/** A coin as it moved: its symbol, decimals and amount in its minor units. Never logged. */
data class PaidCoin(val symbol: String, val decimals: Int, val amountMinor: java.math.BigInteger) {
    override fun toString(): String = "PaidCoin($symbol, ***)"
}

/**
 * Who approves a group's payouts (GW1, decision 2026-11-03): two admins (every admin when there are fewer; an admin
 * asking counts as one), or any one admin.
 */
enum class ApprovalPolicy {
    TWO_ADMINS,
    ANY_ADMIN,
    ;

    /** How many approvals a request needs in a group with [admins] admins. */
    fun needed(admins: Int): Int =
        when (this) {
            TWO_ADMINS -> minOf(2, admins).coerceAtLeast(1)
            ANY_ADMIN -> 1
        }
}

/** A group's money settings; [updatedBy] is null for a group that never chose (the default). */
data class GroupSettings(
    val chatId: ChatId,
    val approvalPolicy: ApprovalPolicy,
    val updatedBy: UserId?,
    val updatedAt: Instant?,
) {
    companion object {
        fun default(chatId: ChatId) = GroupSettings(chatId, ApprovalPolicy.TWO_ADMINS, null, null)
    }
}

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
    /** Towards the target: contributions since it was set. */
    val contributedMinor: Long,
    val paidOutMinor: Long,
    /** What the ledger says the group wallet holds: every contribution minus every payout. W1 has the real balance. */
    val heldMinor: Long,
    val myContributedMinor: Long,
    val contributorsMinor: Map<UserId, Long>,
    val openRequests: List<Payout>,
    val isAdmin: Boolean,
    /** Whether the caller signs for the group wallet (it was the treasurer; the field name stays for the app). */
    val isTreasurer: Boolean,
    val wallet: GroupWalletSummary? = null,
    val settings: GroupSettings? = null,
)

/**
 * CH7: a group's money as one member sees it, target or not (decision 2026-11-03): its wallet (null before the owner
 * sets one up), who approves payouts, the target if any with what was put towards it, and the open requests.
 */
data class GroupMoney(
    val chatId: ChatId,
    val wallet: GroupWalletSummary?,
    val settings: GroupSettings,
    val goal: Goal?,
    val memberCount: Int,
    /** Towards the target (zero without one). */
    val contributedMinor: Long,
    val myContributedMinor: Long,
    val contributorsMinor: Map<UserId, Long>,
    val paidOutMinor: Long,
    val openRequests: List<Payout>,
    val isAdmin: Boolean,
    val isOwner: Boolean,
    val isSigner: Boolean,
)

/** A confirmed transfer as `wallet.TransferConfirmed` tells it, in chama's words. Never logged. */
data class WalletTransfer(
    val transferId: TransferId,
    /** The wallet it left: a person, or the group wallet's id when [fromGroupId] is set. */
    val senderId: UserId,
    /** In US dollars, micro-dollars, at the price it was paid at (2026-11-07; the USDC amount itself before). */
    val amountMinor: Long,
    val chatId: ChatId?,
    val fromGroupId: ChatId?,
    val toGroupId: ChatId?,
    val payoutRequestId: java.util.UUID?,
    /** The coin that moved, when the wallet said its decimals. */
    val paid: PaidCoin? = null,
) {
    override fun toString(): String = "WalletTransfer($transferId)"
}

data class SetSettingsCommand(val userId: UserId, val chatId: ChatId, val approvalPolicy: ApprovalPolicy)

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

/**
 * A chama for Asha (decision 2026-10-31): the member's view, the names of the people it mentions, and, for one chama
 * ([isDetailed]), its latest ledger lines and everyone's contributions.
 */
data class ChamaDigest(
    val view: ChamaView,
    val names: Map<UserId, String>,
    val recent: List<LedgerEntry>,
    val isDetailed: Boolean,
)

/** A member's payment into a group's wallet that is on its way (`wallet.TransferSubmitted`), in chama's words. Never logged. */
data class SubmittedContribution(
    val transferId: TransferId,
    val senderId: UserId,
    val chatId: ChatId,
    val paid: PaidCoin,
    /** Its value in micro-dollars, null when the coin's price wasn't kept. */
    val usdMinor: Long?,
) {
    override fun toString(): String = "SubmittedContribution($transferId)"
}
