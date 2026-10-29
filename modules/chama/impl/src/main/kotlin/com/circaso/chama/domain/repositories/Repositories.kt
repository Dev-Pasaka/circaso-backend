package com.circaso.chama.domain.repositories

import com.circaso.chama.api.RequestId
import com.circaso.chama.domain.models.Approval
import com.circaso.chama.domain.models.Contribution
import com.circaso.chama.domain.models.Goal
import com.circaso.chama.domain.models.Payout
import com.circaso.chama.domain.models.RequestState
import com.circaso.messaging.api.ChatId
import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferId
import java.time.Instant

interface GoalRepository {
    suspend fun find(chatId: ChatId): Goal?

    /** Inserts or replaces the chat's goal (one per chat). */
    suspend fun save(goal: Goal)
}

interface ContributionRepository {
    /** Records it unless the transfer was recorded before; true if this call recorded it. */
    suspend fun insert(contribution: Contribution): Boolean

    suspend fun list(
        chatId: ChatId,
        limit: Int,
        before: Instant?,
    ): List<Contribution>

    /** Per member, the sum of what they put in. */
    suspend fun totals(chatId: ChatId): Map<UserId, Long>
}

interface RequestRepository {
    suspend fun insert(request: Payout)

    suspend fun find(requestId: RequestId): Payout?

    suspend fun byTransfer(transferId: TransferId): Payout?

    suspend fun open(chatId: ChatId): List<Payout>

    suspend fun paid(
        chatId: ChatId,
        limit: Int,
        before: Instant?,
    ): List<Payout>

    suspend fun addApproval(requestId: RequestId, approval: Approval): Boolean

    /** Moves the request to [to] only from one of [from]; the request as it now is, or null when it wasn't there. */
    suspend fun move(
        requestId: RequestId,
        to: RequestState,
        from: Set<RequestState>,
        transferId: TransferId? = null,
        at: Instant,
    ): Payout?

    /** Marks every `PENDING` request past [now] as `EXPIRED`; how many it moved. */
    suspend fun expire(now: Instant): Int

    suspend fun paidOutTotal(chatId: ChatId): Long
}
