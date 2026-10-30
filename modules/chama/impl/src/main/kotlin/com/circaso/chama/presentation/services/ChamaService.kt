package com.circaso.chama.presentation.services

import com.circaso.chama.api.RequestId
import com.circaso.chama.domain.models.AskPayoutCommand
import com.circaso.chama.domain.models.ChamaView
import com.circaso.chama.domain.models.Decision
import com.circaso.chama.domain.models.Goal
import com.circaso.chama.domain.models.LedgerEntry
import com.circaso.chama.domain.models.Payout
import com.circaso.chama.domain.models.SetGoalCommand
import com.circaso.chama.domain.usecases.AskPayout
import com.circaso.chama.domain.usecases.AttachPayment
import com.circaso.chama.domain.usecases.DecidePayout
import com.circaso.chama.domain.usecases.GetChama
import com.circaso.chama.domain.usecases.ListLedger
import com.circaso.chama.domain.usecases.SetGoal
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferId
import java.time.Instant

/** The goal, the ledger and the requests. */
class ChamaService(
    private val setGoal: SetGoal,
    private val getChama: GetChama,
    private val listLedger: ListLedger,
    private val createRequest: AskPayout,
    private val decideRequest: DecidePayout,
    private val attachPayment: AttachPayment,
) {
    suspend fun setGoal(command: SetGoalCommand): Resource<Goal> = setGoal.invoke(command)

    suspend fun get(userId: UserId, chatId: ChatId): Resource<ChamaView> = getChama(userId, chatId)

    suspend fun ledger(
        userId: UserId,
        chatId: ChatId,
        limit: Int,
        before: Instant?,
    ): Resource<List<LedgerEntry>> = listLedger(userId, chatId, limit, before)

    suspend fun request(command: AskPayoutCommand): Resource<Payout> = createRequest.invoke(command)

    suspend fun decide(
        userId: UserId,
        requestId: RequestId,
        decision: Decision,
    ): Resource<Payout> = decideRequest(userId, requestId, decision)

    suspend fun attach(
        userId: UserId,
        requestId: RequestId,
        transferId: TransferId,
    ): Resource<Payout> = attachPayment(userId, requestId, transferId)
}
