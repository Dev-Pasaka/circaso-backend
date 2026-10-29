package com.circaso.chama.domain.usecases

import com.circaso.chama.api.RequestId
import com.circaso.chama.domain.errors.ChamaError
import com.circaso.chama.domain.models.Approval
import com.circaso.chama.domain.models.Decision
import com.circaso.chama.domain.models.Payout
import com.circaso.chama.domain.models.RequestState
import com.circaso.chama.domain.repositories.RequestRepository
import com.circaso.chama.domain.support.Members
import com.circaso.chama.domain.support.RequestFrames
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Clock

/**
 * `POST /chama/requests/{requestId}/approve` and `/decline`: an admin decides once. Enough approvals move it to
 * `APPROVED` (the treasurer can pay); one decline moves it to `DECLINED`.
 */
class DecidePayout(
    private val members: Members,
    private val requests: RequestRepository,
    private val requestFrames: RequestFrames,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        userId: UserId,
        requestId: RequestId,
        decision: Decision,
    ): Resource<Payout> {
        val request = requests.find(requestId) ?: return Resource.error(ChamaError.RequestNotFound)
        val admins =
            when (val r = members.requireAdmin(request.chatId, userId)) {
                is Resource.Error -> return r
                is Resource.Success -> r.data
            }
        if (request.state != RequestState.PENDING) return Resource.error(ChamaError.RequestClosed)
        if (request.approvals.any { it.userId == userId }) return Resource.error(ChamaError.AlreadyDecided)

        val now = clock.instant()
        if (!requests.addApproval(
                requestId,
                Approval(userId, decision, now),
            )
        ) {
            return Resource.error(ChamaError.AlreadyDecided)
        }
        val approvals = request.approvals + Approval(userId, decision, now)
        val approved = approvals.count { it.decision == Decision.APPROVE }
        val next =
            when {
                decision == Decision.DECLINE -> RequestState.DECLINED
                approved >= request.approvalsNeeded -> RequestState.APPROVED
                else -> null
            }
        val updated =
            if (next != null) {
                requests.move(requestId, next, setOf(RequestState.PENDING), at = now)
                    ?: return Resource.error(ChamaError.RequestClosed)
            } else {
                request.copy(approvals = approvals, updatedAt = now)
            }
        requestFrames.changed(updated, members.members(request.chatId), admins)
        return Resource.success(updated)
    }
}
