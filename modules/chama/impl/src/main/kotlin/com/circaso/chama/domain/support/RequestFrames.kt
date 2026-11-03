package com.circaso.chama.domain.support

import com.circaso.chama.domain.models.Payout
import com.circaso.chama.domain.models.RequestState
import com.circaso.chama.domain.ports.ChamaFrame
import com.circaso.chama.domain.ports.FrameSender
import com.circaso.chama.domain.repositories.GoalRepository
import com.circaso.shared.UserId
import com.circaso.wallet.api.Wallets

/** Words a request's change for the members: who still has to decide, and what the gateway may push. */
class RequestFrames(
    private val goals: GoalRepository,
    private val members: Members,
    private val frames: FrameSender,
    private val wallets: Wallets,
) {
    suspend fun changed(
        request: Payout,
        everyone: Set<UserId>,
        admins: Set<UserId>,
    ) {
        val goalName = goals.find(request.chatId)?.name ?: ""
        val decided = request.approvals.map { it.userId }.toSet()
        val waitingOn = if (request.state == RequestState.PENDING) (admins - decided).toList() else emptyList()
        frames.send(
            everyone,
            ChamaFrame.RequestUpdated(
                requestId = request.requestId,
                chatId = request.chatId,
                state = request.state.name,
                requestedBy = request.requestedBy,
                requesterName = members.name(request.requestedBy),
                goalName = goalName,
                amount = Amounts.usdc(request.amountMinor),
                approvals = request.approvedBy.size,
                approvalsNeeded = request.approvalsNeeded,
                waitingOn = waitingOn,
                signerId = wallets.groupWallet(request.chatId)?.signerId,
            ),
        )
    }
}
