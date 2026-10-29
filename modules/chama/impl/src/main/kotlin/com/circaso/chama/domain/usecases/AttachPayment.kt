package com.circaso.chama.domain.usecases

import com.circaso.chama.api.RequestId
import com.circaso.chama.domain.errors.ChamaError
import com.circaso.chama.domain.models.Payout
import com.circaso.chama.domain.models.RequestState
import com.circaso.chama.domain.repositories.GoalRepository
import com.circaso.chama.domain.repositories.RequestRepository
import com.circaso.chama.domain.support.Members
import com.circaso.chama.domain.support.RequestFrames
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.api.Wallets
import java.time.Clock

/**
 * `POST /chama/requests/{requestId}/payment`: the treasurer paid an approved request with their own transfer (W4,
 * from the chat) and links it. The transfer must be theirs, to the request's payee, for its amount, from the chat;
 * the request is `PAID` once the transfer is confirmed (now, or when the wallet's event arrives).
 */
class AttachPayment(
    private val members: Members,
    private val goals: GoalRepository,
    private val requests: RequestRepository,
    private val wallets: Wallets,
    private val requestFrames: RequestFrames,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        userId: UserId,
        requestId: RequestId,
        transferId: TransferId,
    ): Resource<Payout> {
        val request = requests.find(requestId) ?: return Resource.error(ChamaError.RequestNotFound)
        when (val r = members.requireMember(request.chatId, userId)) {
            is Resource.Error -> return r
            is Resource.Success -> Unit
        }
        val goal = goals.find(request.chatId) ?: return Resource.error(ChamaError.NoGoal)
        if (userId != goal.treasurerId) return Resource.error(ChamaError.NotTreasurer)
        if (request.state != RequestState.APPROVED) return Resource.error(ChamaError.NotApproved)

        val transfer = wallets.transfer(transferId) ?: return Resource.error(ChamaError.TransferMismatch)
        val samePayee =
            (request.payeeId != null && transfer.recipientId == request.payeeId) ||
                (
                    request.payeeAddress != null &&
                        transfer.destinationAddress.equals(request.payeeAddress, ignoreCase = true)
                )
        val matches =
            transfer.senderId == userId &&
                samePayee &&
                transfer.amount.amountMinor == request.amountMinor.toString() &&
                transfer.chatId == request.chatId &&
                transfer.state != "FAILED" &&
                transfer.state != "EXPIRED"
        if (!matches) return Resource.error(ChamaError.TransferMismatch)

        val now = clock.instant()
        val to = if (transfer.state == "CONFIRMED") RequestState.PAID else RequestState.APPROVED
        val updated =
            requests.move(requestId, to, setOf(RequestState.APPROVED), transferId = transferId, at = now)
                ?: return Resource.error(ChamaError.RequestClosed)
        requestFrames.changed(updated, members.members(request.chatId), members.admins(request.chatId))
        return Resource.success(updated)
    }
}
