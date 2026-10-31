package com.circaso.chama.presentation.mappers

import com.circaso.chama.api.AgentChamaDto
import com.circaso.chama.api.AgentChamaPersonDto
import com.circaso.chama.api.AgentChamaRequestDto
import com.circaso.chama.api.AgentChamaResponse
import com.circaso.chama.api.AgentChamasResponse
import com.circaso.chama.api.AgentContributorDto
import com.circaso.chama.api.AgentLedgerLineDto
import com.circaso.chama.domain.models.ChamaDigest
import com.circaso.chama.domain.models.LedgerEntry
import com.circaso.chama.domain.models.Payout
import com.circaso.chama.domain.support.Amounts
import com.circaso.shared.UserId

private const val MAX_OPEN_REQUESTS = 10
private const val MAX_CONTRIBUTORS = 30
private const val PERCENT = 100

fun List<ChamaDigest>.toAgentResponse() = AgentChamasResponse(map { it.toAgentDto() })

fun ChamaDigest.toAgentResponse() = AgentChamaResponse(toAgentDto())

private fun ChamaDigest.toAgentDto(): AgentChamaDto {
    val goal = view.goal
    val progress = if (goal.targetMinor <= 0) 0 else (view.contributedMinor * PERCENT / goal.targetMinor).toInt()
    return AgentChamaDto(
        chatId = goal.chatId.toString(),
        name = goal.name,
        asset = "USDC",
        target = Amounts.whole(goal.targetMinor),
        contributed = Amounts.whole(view.contributedMinor),
        paidOut = Amounts.whole(view.paidOutMinor),
        held = Amounts.whole(view.heldMinor),
        myContribution = Amounts.whole(view.myContributedMinor),
        progressPercent = progress,
        deadline = goal.deadline?.toString(),
        treasurer = person(goal.treasurerId),
        memberCount = view.memberCount,
        isAdmin = view.isAdmin,
        isTreasurer = view.isTreasurer,
        openRequests = view.openRequests.take(MAX_OPEN_REQUESTS).map { request(it) },
        contributors = if (isDetailed) contributorsOf() else emptyList(),
        recent = recent.map { line(it) },
    )
}

/** Biggest first; only for one chama (the list leaves them out to stay small). */
private fun ChamaDigest.contributorsOf(): List<AgentContributorDto> =
    view.contributorsMinor.entries
        .sortedByDescending { it.value }
        .take(MAX_CONTRIBUTORS)
        .map { AgentContributorDto(person(it.key), Amounts.whole(it.value)) }

private fun ChamaDigest.person(userId: UserId) = AgentChamaPersonDto(userId.toString(), names[userId])

private fun ChamaDigest.request(request: Payout) =
    AgentChamaRequestDto(
        requestId = request.requestId.toString(),
        state = request.state.name,
        amount = Amounts.whole(request.amountMinor),
        requestedBy = person(request.requestedBy),
        payee = request.payeeId?.let { person(it) },
        payeeName = request.payeeName,
        note = request.note,
        approvals = request.approvedBy.size,
        approvalsNeeded = request.approvalsNeeded,
        expiresAt = request.expiresAt.toString(),
    )

private fun ChamaDigest.line(entry: LedgerEntry) =
    when (entry) {
        is LedgerEntry.In -> {
            AgentLedgerLineDto(
                kind = "IN",
                amount = Amounts.whole(entry.amountMinor),
                at = entry.at.toString(),
                person = person(entry.contribution.userId),
            )
        }

        is LedgerEntry.Out -> {
            AgentLedgerLineDto(
                kind = "OUT",
                amount = Amounts.whole(entry.amountMinor),
                at = entry.at.toString(),
                person = entry.request.payeeId?.let { person(it) },
                note = entry.request.payeeName ?: entry.request.note,
            )
        }
    }
