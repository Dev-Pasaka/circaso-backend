package com.circaso.chama.domain.usecases

import com.circaso.chama.api.RequestId
import com.circaso.chama.domain.errors.ChamaError
import com.circaso.chama.domain.models.Payout
import com.circaso.chama.domain.models.RequestState
import com.circaso.chama.domain.repositories.RequestRepository
import com.circaso.chama.domain.support.Members
import com.circaso.chama.domain.support.RequestFrames
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.api.Wallets
import java.time.Clock

/**
 * `POST /chama/requests/{requestId}/payment` (CH6): the group wallet's signer links the transfer that pays an approved
 * request, so every member sees it as being paid before it is confirmed. Optional since group wallets (decision
 * 2026-11-03): W4 with `payoutRequestId` already ties the transfer to the request, and the request is `PAID` when the
 * wallet confirms it. The transfer must leave this group's wallet, to the request's payee, for its amount; it is
 * `PAID` at once when already confirmed.
 */
class AttachPayment(
    private val members: Members,
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
        val wallet = wallets.groupWallet(request.chatId) ?: return Resource.error(ChamaError.NoWallet)
        if (userId != wallet.signerId) return Resource.error(ChamaError.NotSigner)
        if (request.state == RequestState.PAID && request.transferId == transferId) return Resource.success(request)
        if (request.state != RequestState.APPROVED) return Resource.error(ChamaError.NotApproved)

        val transfer = wallets.transfer(transferId) ?: return Resource.error(ChamaError.TransferMismatch)
        val samePayee =
            (request.payeeId != null && transfer.recipientId == request.payeeId) ||
                (
                    request.payeeAddress != null &&
                        transfer.destinationAddress.equals(request.payeeAddress, ignoreCase = true)
                )
        val matches =
            transfer.fromGroupId == request.chatId &&
                (transfer.payoutRequestId == null || transfer.payoutRequestId == requestId.value) &&
                samePayee &&
                transfer.amount.amountMinor == request.amountMinor.toString() &&
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
