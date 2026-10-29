package com.circaso.chama.domain.usecases

import com.circaso.chama.api.RequestId
import com.circaso.chama.domain.errors.ChamaError
import com.circaso.chama.domain.models.Approval
import com.circaso.chama.domain.models.AskPayoutCommand
import com.circaso.chama.domain.models.Decision
import com.circaso.chama.domain.models.Payout
import com.circaso.chama.domain.models.RequestState
import com.circaso.chama.domain.repositories.GoalRepository
import com.circaso.chama.domain.repositories.RequestRepository
import com.circaso.chama.domain.support.Members
import com.circaso.chama.domain.support.RequestFrames
import com.circaso.shared.Resource
import java.time.Clock
import java.time.Duration

/**
 * `POST /chama/chats/{chatId}/requests`: a member asks the group to pay someone. It needs two admins' approval (every
 * admin when there are fewer); an admin asking counts as their own approval. Open for 48 hours. Every member hears
 * `request.updated`; the admins still to decide get the push.
 */
class AskPayout(
    private val members: Members,
    private val goals: GoalRepository,
    private val requests: RequestRepository,
    private val requestFrames: RequestFrames,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: AskPayoutCommand): Resource<Payout> {
        val everyone =
            when (val r = members.requireMember(command.chatId, command.userId)) {
                is Resource.Error -> return r
                is Resource.Success -> r.data
            }
        goals.find(command.chatId) ?: return Resource.error(ChamaError.NoGoal)
        if (command.amountMinor <= 0) return Resource.error(ChamaError.AmountInvalid)
        val hasPayee = command.payeeId != null
        val hasAddress = !command.payeeAddress.isNullOrBlank()
        if (hasPayee == hasAddress) return Resource.error(ChamaError.PayeeInvalid)

        val admins = members.admins(command.chatId)
        val needed = minOf(APPROVALS, admins.size).coerceAtLeast(1)
        val now = clock.instant()
        val own = if (command.userId in admins) listOf(Approval(command.userId, Decision.APPROVE, now)) else emptyList()
        val request =
            Payout(
                requestId = RequestId.new(),
                chatId = command.chatId,
                requestedBy = command.userId,
                payeeId = command.payeeId,
                payeeAddress = command.payeeAddress?.trim(),
                payeeName =
                    command.payeeName
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.take(MAX_NAME),
                amountMinor = command.amountMinor,
                note =
                    command.note
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.take(MAX_NOTE),
                state = if (own.size >= needed) RequestState.APPROVED else RequestState.PENDING,
                approvalsNeeded = needed,
                approvals = own,
                transferId = null,
                expiresAt = now.plus(OPEN_FOR),
                createdAt = now,
                updatedAt = now,
            )
        requests.insert(request)
        requestFrames.changed(request, everyone, admins)
        return Resource.success(request)
    }

    private companion object {
        const val APPROVALS = 2
        const val MAX_NOTE = 140
        const val MAX_NAME = 60
        val OPEN_FOR: Duration = Duration.ofHours(48)
    }
}
