package com.circaso.chama.domain.usecases

import com.circaso.chama.domain.errors.ChamaError
import com.circaso.chama.domain.models.ChamaView
import com.circaso.chama.domain.repositories.ContributionRepository
import com.circaso.chama.domain.repositories.GoalRepository
import com.circaso.chama.domain.repositories.RequestRepository
import com.circaso.chama.domain.support.Members
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** `GET /chama/chats/{chatId}`: the goal, the totals, who put in what, and the requests still open. Members only. */
class GetChama(
    private val members: Members,
    private val goals: GoalRepository,
    private val contributions: ContributionRepository,
    private val requests: RequestRepository,
) {
    suspend operator fun invoke(userId: UserId, chatId: ChatId): Resource<ChamaView> {
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
}
