package com.circaso.chama.presentation.api

import com.circaso.chama.api.ChamaFrames
import com.circaso.chama.api.ChamaStatement
import com.circaso.chama.api.ChamaStatementLine
import com.circaso.chama.api.Chamas
import com.circaso.chama.domain.models.ChamaDigest
import com.circaso.chama.domain.models.LedgerEntry
import com.circaso.chama.presentation.services.ChamaEventsService
import com.circaso.messaging.api.ChatId
import com.circaso.shared.CircasoJson
import com.circaso.shared.RealtimeEnvelope
import com.circaso.shared.RealtimeReplay
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.getOrNull
import com.circaso.wallet.api.ApprovedPayout
import com.circaso.wallet.api.PayoutApprovals
import java.util.UUID

/** chama:api. */
class ChamasImpl(private val events: ChamaEventsService) : Chamas {
    override suspend fun hasGoal(chatId: ChatId): Boolean = events.hasGoal(chatId)

    override suspend fun statement(
        chatId: ChatId,
        viewer: UserId,
        lines: Int,
    ): ChamaStatement? = events.statement(viewer, chatId, lines).getOrNull()?.toStatement()
}

private fun ChamaDigest.toStatement(): ChamaStatement =
    ChamaStatement(
        goalName = view.goal.name,
        targetMinor = view.goal.targetMinor,
        contributedMinor = view.contributedMinor,
        paidOutMinor = view.paidOutMinor,
        heldMinor = view.heldMinor,
        memberCount = view.memberCount,
        deadline = view.goal.deadline,
        lines = recent.map { it.toLine(names) },
    )

private fun LedgerEntry.toLine(names: Map<UserId, String>): ChamaStatementLine =
    when (this) {
        is LedgerEntry.In -> {
            ChamaStatementLine(
                at = at,
                isIncoming = true,
                amountMinor = amountMinor,
                personName = names[contribution.userId],
                payeeName = null,
                note = null,
            )
        }

        is LedgerEntry.Out -> {
            ChamaStatementLine(
                at = at,
                isIncoming = false,
                amountMinor = amountMinor,
                personName = request.payeeId?.let(names::get),
                payeeName = request.payeeName,
                note = request.note,
            )
        }
    }

/**
 * wallet:api's guard (decision 2026-11-03, option A): the wallet asks before it prepares a payment from a group wallet
 * whether the admins approved that payout. Bound in chama's Koin module, as identity's `DeletionGuard` is in wallet's.
 */
class ChamaPayoutApprovals(private val events: ChamaEventsService) : PayoutApprovals {
    override suspend fun approved(chatId: ChatId, requestId: UUID): ApprovedPayout? =
        events.approvedPayout(chatId, requestId)
}

/** The gateway's replay after a reconnect: the frames since `seq`, or a `resync` when the gap is older than what is kept. */
class ChamaRealtimeReplay(private val events: ChamaEventsService) : RealtimeReplay {
    override suspend fun since(userId: UserId, seq: Long): Resource<List<RealtimeEnvelope>> {
        val frames =
            events.missedFrames(userId, seq).getOrNull()
                ?: return Resource.success(listOf(RealtimeEnvelope.resync(ChamaFrames.MODULE, seq)))
        return Resource.success(frames.map { CircasoJson.decodeFromString(RealtimeEnvelope.serializer(), it.json) })
    }
}
