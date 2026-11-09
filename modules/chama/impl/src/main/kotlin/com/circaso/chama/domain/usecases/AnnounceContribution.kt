package com.circaso.chama.domain.usecases

import com.circaso.chama.domain.models.SubmittedContribution
import com.circaso.chama.domain.ports.ChamaFrame
import com.circaso.chama.domain.ports.FrameSender
import com.circaso.chama.domain.support.Amounts
import com.circaso.chama.domain.support.Members
import com.circaso.shared.Resource

/**
 * On `wallet.TransferSubmitted` (2026-11-09): a member's payment into the group's wallet is on its way. Every member
 * hears `contribution.submitted` at once, so a group call shows it landing on every phone while the chain confirms it;
 * nothing is recorded and the group's money doesn't move until `wallet.TransferConfirmed` ([RecordTransfer]). A payment
 * from someone who isn't a member (they left meanwhile) is not announced.
 */
class AnnounceContribution(private val members: Members, private val frames: FrameSender) {
    suspend operator fun invoke(payment: SubmittedContribution): Resource<Unit> {
        val everyone = members.members(payment.chatId)
        if (payment.senderId !in everyone) return Resource.success(Unit)
        frames.send(
            everyone,
            ChamaFrame.ContributionSubmitted(
                payment.chatId,
                payment.transferId.value.toString(),
                payment.senderId,
                members.name(payment.senderId),
                Amounts.coin(payment.paid),
                payment.usdMinor?.let(Amounts::usdc),
            ),
        )
        return Resource.success(Unit)
    }
}
