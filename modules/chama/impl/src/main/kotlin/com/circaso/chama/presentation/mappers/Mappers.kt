package com.circaso.chama.presentation.mappers

import com.circaso.chama.api.RequestId
import com.circaso.chama.domain.errors.ChamaError
import com.circaso.chama.domain.models.AskPayoutCommand
import com.circaso.chama.domain.models.ChamaView
import com.circaso.chama.domain.models.Goal
import com.circaso.chama.domain.models.LedgerEntry
import com.circaso.chama.domain.models.Payout
import com.circaso.chama.domain.models.SetGoalCommand
import com.circaso.chama.domain.support.Amounts
import com.circaso.chama.presentation.dto.ApprovalDto
import com.circaso.chama.presentation.dto.ChamaResponse
import com.circaso.chama.presentation.dto.ContributorDto
import com.circaso.chama.presentation.dto.CreateRequestRequest
import com.circaso.chama.presentation.dto.GoalDto
import com.circaso.chama.presentation.dto.LedgerEntryDto
import com.circaso.chama.presentation.dto.LedgerResponse
import com.circaso.chama.presentation.dto.RequestDto
import com.circaso.chama.presentation.dto.SetGoalRequest
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferId
import java.time.Instant

fun chatIdOf(raw: String?): Resource<ChatId> =
    raw?.let { ChatId.parse(it) }?.let { Resource.success(it) }
        ?: Resource.error(SharedError.Malformed("chatId", "is not a chat id"))

fun requestIdOf(raw: String?): Resource<RequestId> =
    raw?.let { RequestId.parse(it) }?.let { Resource.success(it) }
        ?: Resource.error(SharedError.Malformed("requestId", "is not a request id"))

fun transferIdOf(raw: String?): Resource<TransferId> =
    raw?.let { TransferId.parse(it) }?.let { Resource.success(it) }
        ?: Resource.error(SharedError.Malformed("transferId", "is not a transfer id"))

fun SetGoalRequest.toCommand(userId: UserId, chatId: ChatId): Resource<SetGoalCommand> {
    val target =
        Amounts.parse(this.target.amountMinor, this.target.amount)
            ?: return Resource.error(ChamaError.GoalInvalid("target", "must be an amount above zero"))
    val deadline =
        this.deadline?.let {
            runCatching { Instant.parse(it) }.getOrNull()
                ?: return Resource.error(SharedError.Malformed("deadline", "is not a time"))
        }
    val treasurer =
        this.treasurerUserId?.let {
            UserId.parse(it)
                ?: return Resource.error(SharedError.Malformed("treasurerUserId", "is not a user id"))
        }
    return Resource.success(SetGoalCommand(userId, chatId, name, target, deadline, treasurer))
}

fun CreateRequestRequest.toCommand(userId: UserId, chatId: ChatId): Resource<AskPayoutCommand> {
    val amount =
        Amounts.parse(this.amount.amountMinor, this.amount.amount) ?: return Resource.error(ChamaError.AmountInvalid)
    val payee =
        to.userId?.let {
            UserId.parse(it)
                ?: return Resource.error(SharedError.Malformed("to.userId", "is not a user id"))
        }
    return Resource.success(AskPayoutCommand(userId, chatId, payee, to.address, to.name, amount, note))
}

fun Goal.toDto() =
    GoalDto(
        chatId.toString(),
        name,
        Amounts.usdc(targetMinor),
        deadline?.toString(),
        treasurerId.toString(),
        createdBy.toString(),
        updatedAt.toString(),
        version,
    )

fun Payout.toDto() =
    RequestDto(
        requestId = requestId.toString(),
        chatId = chatId.toString(),
        requestedBy = requestedBy.toString(),
        payeeUserId = payeeId?.toString(),
        payeeAddress = payeeAddress,
        payeeName = payeeName,
        amount = Amounts.usdc(amountMinor),
        note = note,
        state = state.name,
        approvalsNeeded = approvalsNeeded,
        approvals = approvals.map { ApprovalDto(it.userId.toString(), it.decision.name, it.at.toString()) },
        transferId = transferId?.toString(),
        expiresAt = expiresAt.toString(),
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )

fun ChamaView.toResponse() =
    ChamaResponse(
        goal = goal.toDto(),
        memberCount = memberCount,
        contributed = Amounts.usdc(contributedMinor),
        paidOut = Amounts.usdc(paidOutMinor),
        held = Amounts.usdc(heldMinor),
        myContribution = Amounts.usdc(myContributedMinor),
        contributors =
            contributorsMinor.entries
                .sortedByDescending {
                    it.value
                }.map { ContributorDto(it.key.toString(), Amounts.usdc(it.value)) },
        openRequests = openRequests.map { it.toDto() },
        isAdmin = isAdmin,
        isTreasurer = isTreasurer,
    )

fun List<LedgerEntry>.toResponse(limit: Int) =
    LedgerResponse(
        entries =
            map { entry ->
                when (entry) {
                    is LedgerEntry.In -> {
                        LedgerEntryDto(
                            "IN",
                            entry.at.toString(),
                            Amounts.usdc(entry.amountMinor),
                            userId = entry.contribution.userId.toString(),
                            transferId = entry.contribution.transferId.toString(),
                        )
                    }

                    is LedgerEntry.Out -> {
                        LedgerEntryDto(
                            "OUT",
                            entry.at.toString(),
                            Amounts.usdc(entry.amountMinor),
                            userId = entry.request.requestedBy.toString(),
                            transferId = entry.request.transferId?.toString(),
                            request = entry.request.toDto(),
                        )
                    }
                }
            },
        next = if (size >= limit) lastOrNull()?.at?.toString() else null,
    )
