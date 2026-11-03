package com.circaso.chama.presentation.mappers

import com.circaso.chama.api.RequestId
import com.circaso.chama.domain.errors.ChamaError
import com.circaso.chama.domain.models.ApprovalPolicy
import com.circaso.chama.domain.models.AskPayoutCommand
import com.circaso.chama.domain.models.ChamaView
import com.circaso.chama.domain.models.Goal
import com.circaso.chama.domain.models.GroupMoney
import com.circaso.chama.domain.models.GroupSettings
import com.circaso.chama.domain.models.LedgerEntry
import com.circaso.chama.domain.models.Payout
import com.circaso.chama.domain.models.SetGoalCommand
import com.circaso.chama.domain.models.SetSettingsCommand
import com.circaso.chama.domain.support.Amounts
import com.circaso.chama.presentation.dto.ApprovalDto
import com.circaso.chama.presentation.dto.ChamaResponse
import com.circaso.chama.presentation.dto.ContributorDto
import com.circaso.chama.presentation.dto.CreateRequestRequest
import com.circaso.chama.presentation.dto.GoalDto
import com.circaso.chama.presentation.dto.GroupMoneyResponse
import com.circaso.chama.presentation.dto.GroupSettingsDto
import com.circaso.chama.presentation.dto.GroupSettingsRequest
import com.circaso.chama.presentation.dto.GroupWalletDto
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
    return Resource.success(SetGoalCommand(userId, chatId, name, target, deadline))
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

/** [signerId] is who sends from the group's wallet; a goal from the treasurer model names its treasurer instead. */
fun Goal.toDto(signerId: UserId? = null) =
    GoalDto(
        chatId.toString(),
        name,
        Amounts.usdc(targetMinor),
        deadline?.toString(),
        (treasurerId ?: signerId)?.toString(),
        createdBy.toString(),
        updatedAt.toString(),
        version,
    )

fun GroupSettingsRequest.toCommand(userId: UserId, chatId: ChatId): Resource<SetSettingsCommand> {
    val policy =
        ApprovalPolicy.entries.firstOrNull { it.name == approvalPolicy.trim().uppercase() }
            ?: return Resource.error(SharedError.Malformed("approvalPolicy", "is not TWO_ADMINS or ANY_ADMIN"))
    return Resource.success(SetSettingsCommand(userId, chatId, policy))
}

fun GroupSettings.toDto() = GroupSettingsDto(chatId.toString(), approvalPolicy.name, updatedAt?.toString())

private fun Map<UserId, Long>.toContributors() =
    entries.sortedByDescending { it.value }.map { ContributorDto(it.key.toString(), Amounts.usdc(it.value)) }

fun GroupMoney.toResponse() =
    GroupMoneyResponse(
        chatId = chatId.toString(),
        wallet = wallet?.let { GroupWalletDto(it.walletId.toString(), it.status, it.signerId.toString()) },
        approvalPolicy = settings.approvalPolicy.name,
        goal = goal?.toDto(wallet?.signerId),
        memberCount = memberCount,
        contributed = Amounts.usdc(contributedMinor),
        myContribution = Amounts.usdc(myContributedMinor),
        contributors = contributorsMinor.toContributors(),
        paidOut = Amounts.usdc(paidOutMinor),
        openRequests = openRequests.map { it.toDto() },
        isAdmin = isAdmin,
        isOwner = isOwner,
        isSigner = isSigner,
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
        goal = goal.toDto(wallet?.signerId),
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
        walletId = wallet?.walletId?.toString(),
        approvalPolicy = (settings?.approvalPolicy ?: ApprovalPolicy.TWO_ADMINS).name,
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
