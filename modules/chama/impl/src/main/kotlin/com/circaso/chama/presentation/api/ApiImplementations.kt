package com.circaso.chama.presentation.api

import com.circaso.chama.api.ChamaFrames
import com.circaso.chama.api.Chamas
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
