package com.circaso.chama.domain.usecases

import com.circaso.chama.api.ContributionRecorded
import com.circaso.chama.api.MoneyUpdatedFrame
import com.circaso.chama.api.RequestId
import com.circaso.chama.api.RequestPaid
import com.circaso.chama.domain.models.Contribution
import com.circaso.chama.domain.models.DirectPayout
import com.circaso.chama.domain.models.Payout
import com.circaso.chama.domain.models.RequestState
import com.circaso.chama.domain.models.WalletTransfer
import com.circaso.chama.domain.ports.ChamaFrame
import com.circaso.chama.domain.ports.ChamaOutbox
import com.circaso.chama.domain.ports.FrameSender
import com.circaso.chama.domain.repositories.ContributionRepository
import com.circaso.chama.domain.repositories.DirectPayoutRepository
import com.circaso.chama.domain.repositories.GoalRepository
import com.circaso.chama.domain.repositories.RequestRepository
import com.circaso.chama.domain.support.Amounts
import com.circaso.chama.domain.support.Members
import com.circaso.chama.domain.support.RequestFrames
import com.circaso.shared.EventBus
import com.circaso.shared.EventEnvelope
import com.circaso.shared.Resource
import com.circaso.shared.envelope
import java.time.Clock

/**
 * On `wallet.TransferConfirmed` (decision 2026-11-03, group wallets): a member's payment into the group's wallet is a
 * contribution (every member hears `contribution.added`, pushed as "Wanjiru added to Holiday Fund", when the group
 * has a target); a payment out of the group's wallet for an approved request (W4 `payoutRequestId`, or one linked by
 * CH6) moves that request to `PAID`. Either way every member gets `money.updated` (2026-11-04), target or not, so the
 * group's balance and target move live on every phone. Safe to run twice: a contribution is recorded once per transfer, and a request
 * moves only from `APPROVED`. Money the wallet's owner sent straight from it (decision 2026-11-06, no request) is
 * recorded once as a direct payout, and every member hears `money.updated` with `PAYOUT`.
 */
class RecordTransfer(
    private val members: Members,
    private val goals: GoalRepository,
    private val contributions: ContributionRepository,
    private val requests: RequestRepository,
    private val frames: FrameSender,
    private val requestFrames: RequestFrames,
    private val outbox: ChamaOutbox,
    private val bus: EventBus,
    private val payouts: DirectPayoutRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(transfer: WalletTransfer): Resource<Unit> {
        payoutOf(transfer)?.let { request ->
            paid(request, transfer)
            return Resource.success(Unit)
        }
        transfer.fromGroupId?.let { group ->
            sentStraight(group, transfer)
            return Resource.success(Unit)
        }
        val chat = transfer.toGroupId ?: return Resource.success(Unit)
        val everyone = members.members(chat)
        if (transfer.senderId !in everyone) return Resource.success(Unit)
        val now = clock.instant()
        if (!contributions.insert(
                Contribution(transfer.transferId, chat, transfer.senderId, transfer.amountMinor, now),
            )
        ) {
            return Resource.success(Unit)
        }
        publish(
            bus.envelope(
                ContributionRecorded(chat, transfer.senderId, transfer.transferId, Amounts.usdc(transfer.amountMinor)),
            ),
        )
        frames.send(
            everyone,
            ChamaFrame.MoneyUpdated(
                chat,
                transfer.transferId.value.toString(),
                MoneyUpdatedFrame.CONTRIBUTION,
                Amounts.usdc(transfer.amountMinor),
                userId = transfer.senderId,
            ),
        )

        val goal = goals.find(chat)?.takeIf { it.treasurerId == null } ?: return Resource.success(Unit)
        val contributed = contributions.totals(chat, since = goal.createdAt).values.sum()
        frames.send(
            everyone,
            ChamaFrame.ContributionAdded(
                chat,
                transfer.senderId,
                members.name(transfer.senderId),
                goal.name,
                Amounts.usdc(transfer.amountMinor),
                Amounts.usdc(contributed),
                Amounts.usdc(goal.targetMinor),
            ),
        )
        return Resource.success(Unit)
    }

    /**
     * Decision 2026-11-06: the wallet's owner sent money straight from the group's wallet (to a member, themselves,
     * anyone, or an outside address; to another group's wallet too, which is no member's contribution). Recorded once,
     * so the group's paid out and held stay true, and every member hears `money.updated` to reload the balance.
     */
    private suspend fun sentStraight(group: com.circaso.messaging.api.ChatId, transfer: WalletTransfer) {
        val recorded = payouts.insert(DirectPayout(transfer.transferId, group, transfer.amountMinor, clock.instant()))
        if (!recorded) return
        frames.send(
            members.members(group),
            ChamaFrame.MoneyUpdated(
                group,
                transfer.transferId.value.toString(),
                MoneyUpdatedFrame.PAYOUT,
                Amounts.usdc(transfer.amountMinor),
            ),
        )
    }

    /** The request this transfer pays: the one W4 named, if it leaves that request's group, or one CH6 linked. */
    private suspend fun payoutOf(transfer: WalletTransfer): Payout? {
        transfer.payoutRequestId?.let { id ->
            val request = requests.find(RequestId(id)) ?: return null
            return request.takeIf { it.chatId == transfer.fromGroupId }
        }
        return requests.byTransfer(transfer.transferId)
    }

    private suspend fun paid(request: Payout, transfer: WalletTransfer) {
        val paid =
            requests.move(
                request.requestId,
                RequestState.PAID,
                setOf(RequestState.APPROVED),
                transferId = transfer.transferId,
                at = clock.instant(),
            ) ?: return
        val everyone = members.members(paid.chatId)
        requestFrames.changed(paid, everyone, members.admins(paid.chatId))
        frames.send(
            everyone,
            ChamaFrame.MoneyUpdated(
                paid.chatId,
                transfer.transferId.value.toString(),
                MoneyUpdatedFrame.PAYOUT,
                Amounts.usdc(paid.amountMinor),
                requestId = paid.requestId,
            ),
        )
        publish(
            bus.envelope(
                RequestPaid(paid.chatId, paid.requestId, transfer.transferId, Amounts.usdc(paid.amountMinor)),
            ),
        )
    }

    /** The outbox row first, so the relay can finish what a failed publish started. */
    private suspend fun publish(envelope: EventEnvelope) {
        outbox.write(listOf(envelope))
        bus.publish(envelope)
    }
}
