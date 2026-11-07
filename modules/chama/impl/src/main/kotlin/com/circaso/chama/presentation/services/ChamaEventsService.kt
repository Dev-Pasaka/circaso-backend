package com.circaso.chama.presentation.services

import com.circaso.chama.domain.models.ChamaDigest
import com.circaso.chama.domain.models.WalletTransfer
import com.circaso.chama.domain.ports.LoggedFrame
import com.circaso.chama.domain.usecases.ExpireRequests
import com.circaso.chama.domain.usecases.FindApprovedPayout
import com.circaso.chama.domain.usecases.GetChamaStatement
import com.circaso.chama.domain.usecases.HasGoal
import com.circaso.chama.domain.usecases.RecordTransfer
import com.circaso.chama.domain.usecases.ReplayFrames
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.api.ApprovedPayout
import java.util.UUID

/**
 * What other parts of the system tell or ask chama: the wallet's events, the gateway's replay, the purge job, its `api`
 * (share links read a statement through it).
 */
class ChamaEventsService(
    private val recordTransfer: RecordTransfer,
    private val replayFrames: ReplayFrames,
    private val expireRequests: ExpireRequests,
    private val hasGoal: HasGoal,
    private val findApprovedPayout: FindApprovedPayout,
    private val getChamaStatement: GetChamaStatement,
) {
    suspend fun transferConfirmed(transfer: WalletTransfer): Resource<Unit> = recordTransfer(transfer)

    suspend fun approvedPayout(chatId: ChatId, requestId: UUID): ApprovedPayout? = findApprovedPayout(chatId, requestId)

    suspend fun missedFrames(userId: UserId, seq: Long): Resource<List<LoggedFrame>?> = replayFrames(userId, seq)

    suspend fun expire(): Int = expireRequests()

    suspend fun hasGoal(chatId: ChatId): Boolean = hasGoal.invoke(chatId)

    suspend fun statement(
        userId: UserId,
        chatId: ChatId,
        lines: Int,
    ): Resource<ChamaDigest> = getChamaStatement(userId, chatId, lines)
}
