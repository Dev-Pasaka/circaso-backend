package com.circaso.chama.domain.usecases

import com.circaso.chama.api.ContributionRecorded
import com.circaso.chama.api.RequestPaid
import com.circaso.chama.domain.models.Contribution
import com.circaso.chama.domain.models.RequestState
import com.circaso.chama.domain.ports.ChamaFrame
import com.circaso.chama.domain.ports.ChamaOutbox
import com.circaso.chama.domain.ports.FrameSender
import com.circaso.chama.domain.repositories.ContributionRepository
import com.circaso.chama.domain.repositories.GoalRepository
import com.circaso.chama.domain.repositories.RequestRepository
import com.circaso.chama.domain.support.Amounts
import com.circaso.chama.domain.support.Members
import com.circaso.chama.domain.support.RequestFrames
import com.circaso.messaging.api.ChatId
import com.circaso.shared.EventBus
import com.circaso.shared.EventEnvelope
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.envelope
import com.circaso.wallet.api.TransferId
import java.time.Clock

/**
 * On `wallet.TransferConfirmed`: a member's payment to the treasurer from a chat with a goal is a contribution
 * (every member hears `contribution.added`, pushed as "Wanjiru added to Holiday Fund"); a transfer a request names
 * moves that request to `PAID`. Safe to run twice: a contribution is recorded once per transfer, and a request moves
 * only from `APPROVED`.
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
    private val clock: Clock,
) {
    suspend operator fun invoke(
        transferId: TransferId,
        senderId: UserId,
        recipientId: UserId?,
        amountMinor: Long,
        chatId: ChatId?,
    ): Resource<Unit> {
        val now = clock.instant()
        requests.byTransfer(transferId)?.let { request ->
            val paid =
                requests.move(
                    request.requestId,
                    RequestState.PAID,
                    setOf(RequestState.APPROVED),
                    transferId = transferId,
                    at = now,
                )
            if (paid != null) {
                requestFrames.changed(paid, members.members(paid.chatId), members.admins(paid.chatId))
                publish(
                    bus.envelope(RequestPaid(paid.chatId, paid.requestId, transferId, Amounts.usdc(paid.amountMinor))),
                )
            }
            return Resource.success(Unit)
        }
        val chat = chatId ?: return Resource.success(Unit)
        val goal = goals.find(chat) ?: return Resource.success(Unit)
        if (recipientId != goal.treasurerId || senderId == goal.treasurerId) return Resource.success(Unit)
        val everyone = members.members(chat)
        if (senderId !in everyone) return Resource.success(Unit)
        if (!contributions.insert(
                Contribution(transferId, chat, senderId, amountMinor, now),
            )
        ) {
            return Resource.success(Unit)
        }

        val contributed = contributions.totals(chat).values.sum()
        frames.send(
            everyone,
            ChamaFrame.ContributionAdded(
                chat,
                senderId,
                members.name(senderId),
                goal.name,
                Amounts.usdc(amountMinor),
                Amounts.usdc(contributed),
                Amounts.usdc(goal.targetMinor),
            ),
        )
        publish(bus.envelope(ContributionRecorded(chat, senderId, transferId, Amounts.usdc(amountMinor))))
        return Resource.success(Unit)
    }

    /** The outbox row first, so the relay can finish what a failed publish started. */
    private suspend fun publish(envelope: EventEnvelope) {
        outbox.write(listOf(envelope))
        bus.publish(envelope)
    }
}
