package com.circaso.chama.domain.support

import com.circaso.chama.domain.errors.ChamaError
import com.circaso.chama.domain.models.ChamaView
import com.circaso.chama.domain.models.LedgerEntry
import com.circaso.chama.domain.repositories.ContributionRepository
import com.circaso.chama.domain.repositories.GoalRepository
import com.circaso.chama.domain.repositories.RequestRepository
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Instant

/**
 * A chama as one member sees it, and its ledger: the reads behind `GET /chama/chats/{chatId}`, its ledger and
 * Asha's agent routes, so they add up the same way. Members only; a group without a goal is `chama.no_goal`.
 */
class ChamaViews(
    private val members: Members,
    private val goals: GoalRepository,
    private val contributions: ContributionRepository,
    private val requests: RequestRepository,
) {
    suspend fun view(userId: UserId, chatId: ChatId): Resource<ChamaView> {
        val everyone =
            when (val r = members.requireMember(chatId, userId)) {
                is Resource.Error -> return r
                is Resource.Success -> r.data
            }
        val goal = goals.find(chatId) ?: return Resource.error(ChamaError.NoGoal)
        val totals = contributions.totals(chatId)
        val contributed = totals.values.sum()
        val paidOut = requests.paidOutTotal(chatId)
        val admins = members.admins(chatId)
        return Resource.success(
            ChamaView(
                goal = goal,
                memberCount = everyone.size,
                contributedMinor = contributed,
                paidOutMinor = paidOut,
                heldMinor = (contributed - paidOut).coerceAtLeast(0),
                myContributedMinor = totals[userId] ?: 0,
                contributorsMinor = totals,
                openRequests = requests.open(chatId),
                isAdmin = userId in admins,
                isTreasurer = userId == goal.treasurerId,
            ),
        )
    }

    /** Contributions in and payments out, newest first, at most [size]; the caller has checked membership. */
    suspend fun ledger(
        chatId: ChatId,
        size: Int,
        before: Instant?,
    ): List<LedgerEntry> {
        val entries =
            contributions.list(chatId, size, before).map { LedgerEntry.In(it) } +
                requests.paid(chatId, size, before).map { LedgerEntry.Out(it) }
        return entries.sortedByDescending { it.at }.take(size)
    }
}
