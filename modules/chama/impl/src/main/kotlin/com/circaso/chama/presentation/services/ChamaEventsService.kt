package com.circaso.chama.presentation.services

import com.circaso.chama.domain.ports.LoggedFrame
import com.circaso.chama.domain.usecases.ExpireRequests
import com.circaso.chama.domain.usecases.HasGoal
import com.circaso.chama.domain.usecases.RecordTransfer
import com.circaso.chama.domain.usecases.ReplayFrames
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferId

/** What other parts of the system tell or ask chama: the wallet's events, the gateway's replay, the purge job, its `api`. */
class ChamaEventsService(
    private val recordTransfer: RecordTransfer,
    private val replayFrames: ReplayFrames,
    private val expireRequests: ExpireRequests,
    private val hasGoal: HasGoal,
) {
    suspend fun transferConfirmed(
        transferId: TransferId,
        senderId: UserId,
        recipientId: UserId?,
        amountMinor: Long,
        chatId: ChatId?,
    ): Resource<Unit> = recordTransfer(transferId, senderId, recipientId, amountMinor, chatId)

    suspend fun missedFrames(userId: UserId, seq: Long): Resource<List<LoggedFrame>?> = replayFrames(userId, seq)

    suspend fun expire(): Int = expireRequests()

    suspend fun hasGoal(chatId: ChatId): Boolean = hasGoal.invoke(chatId)
}
