package com.circaso.chama.domain.usecases

import com.circaso.chama.domain.errors.ChamaError
import com.circaso.chama.domain.models.LedgerEntry
import com.circaso.chama.domain.repositories.ContributionRepository
import com.circaso.chama.domain.repositories.GoalRepository
import com.circaso.chama.domain.repositories.RequestRepository
import com.circaso.chama.domain.support.Members
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Instant

/** `GET /chama/chats/{chatId}/ledger`: contributions in and payments out, newest first. Members only. */
class ListLedger(
    private val members: Members,
    private val goals: GoalRepository,
    private val contributions: ContributionRepository,
    private val requests: RequestRepository,
) {
    suspend operator fun invoke(
        userId: UserId,
        chatId: ChatId,
        limit: Int,
        before: Instant?,
    ): Resource<List<LedgerEntry>> {
        when (val r = members.requireMember(chatId, userId)) {
            is Resource.Error -> return r
            is Resource.Success -> Unit
        }
        goals.find(chatId) ?: return Resource.error(ChamaError.NoGoal)
        val size = limit.coerceIn(1, MAX)
        val entries =
            contributions.list(chatId, size, before).map { LedgerEntry.In(it) } +
                requests.paid(chatId, size, before).map { LedgerEntry.Out(it) }
        return Resource.success(entries.sortedByDescending { it.at }.take(size))
    }

    private companion object {
        const val MAX = 200
    }
}
