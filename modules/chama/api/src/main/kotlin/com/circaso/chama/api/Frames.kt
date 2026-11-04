package com.circaso.chama.api

import com.circaso.messaging.api.ChatId
import com.circaso.shared.UserId
import com.circaso.wallet.api.WalletAmount
import kotlinx.serialization.Serializable

/**
 * The `data` of each realtime frame `chama` sends, one class per type (decision 2026-10-27). They live in the
 * contract so the gateway reads them typed when it words a push. A push never shows an amount on the lock screen.
 */
object ChamaFrames {
    const val MODULE = "chama"
    const val GOAL_UPDATED = "goal.updated"
    const val CONTRIBUTION_ADDED = "contribution.added"
    const val REQUEST_UPDATED = "request.updated"
    const val GROUP_UPDATED = "group.updated"
    const val MONEY_UPDATED = "money.updated"
}

/**
 * `chama/money.updated`, to every member (added 2026-11-04): money moved in or out of the group's wallet and is
 * confirmed on chain: a member's contribution ([kind] `CONTRIBUTION`, target or not; [userId] is who paid) or an
 * approved payout (`PAYOUT`, with its [requestId]). Reload the group's money (CH7) and the group wallet (W1/W6 `?walletId=`) so its
 * balance and target move while the screen is open. No push (contributions to a target still push through
 * `contribution.added`).
 */
@Serializable
data class MoneyUpdatedFrame(
    val chatId: ChatId,
    val transferId: String,
    val kind: String,
    val amount: WalletAmount,
    val userId: UserId? = null,
    val requestId: RequestId? = null,
) {
    companion object {
        const val CONTRIBUTION = "CONTRIBUTION"
        const val PAYOUT = "PAYOUT"
    }
}

/**
 * `chama/group.updated`, to every member (added 2026-11-03): who approves payouts changed, or the target was removed;
 * reload the group's money (CH7). No push.
 */
@Serializable
data class GroupUpdatedFrame(val chatId: ChatId, val byUserId: UserId)

/** `chama/goal.updated`, to every member: the goal was set or changed; reload the chama. No push. */
@Serializable
data class GoalUpdatedFrame(
    val chatId: ChatId,
    val name: String,
    val target: WalletAmount,
    val contributed: WalletAmount,
    val byUserId: UserId,
)

/** `chama/contribution.added`, to every member: [userId] put money in. Pushed to the others ("Wanjiru added to Holiday Fund"). */
@Serializable
data class ContributionAddedFrame(
    val chatId: ChatId,
    val userId: UserId,
    val displayName: String,
    val goalName: String,
    val amount: WalletAmount,
    val contributed: WalletAmount,
    val target: WalletAmount,
)

/**
 * `chama/request.updated`, to every member: a payment request was made or moved (`PENDING`, `APPROVED`, `DECLINED`,
 * `PAID`, `EXPIRED`). Pushed to the admins who still have to decide while it is `PENDING` ("Otieno needs your
 * approval"), and to the requester when it is approved, declined or paid.
 */
@Serializable
data class RequestUpdatedFrame(
    val requestId: RequestId,
    val chatId: ChatId,
    val state: String,
    val requestedBy: UserId,
    val requesterName: String,
    /** The target's name; empty when the group has none (say "the group wallet"). */
    val goalName: String,
    val amount: WalletAmount,
    val approvals: Int,
    val approvalsNeeded: Int,
    /** The admins whose decision is still missing while `PENDING`. */
    val waitingOn: List<UserId> = emptyList(),
    /** Who pays it once `APPROVED`: the group wallet's signer (added 2026-11-03), pushed then. */
    val signerId: UserId? = null,
)
