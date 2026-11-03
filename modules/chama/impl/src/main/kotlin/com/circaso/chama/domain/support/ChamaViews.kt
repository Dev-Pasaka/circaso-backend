package com.circaso.chama.domain.support

import com.circaso.chama.domain.errors.ChamaError
import com.circaso.chama.domain.models.ChamaView
import com.circaso.chama.domain.models.GroupMoney
import com.circaso.chama.domain.models.LedgerEntry
import com.circaso.chama.domain.repositories.ContributionRepository
import com.circaso.chama.domain.repositories.GoalRepository
import com.circaso.chama.domain.repositories.RequestRepository
import com.circaso.chama.domain.repositories.SettingsRepository
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.api.Wallets
import java.time.Instant

/**
 * A group's money as one member sees it, and its ledger: the reads behind CH1, CH3, CH7 and Asha's agent routes, so
 * they add up the same way. Members only. CH1 (and Asha) need a target: a group without one is `chama.no_goal`
 * there; CH7 answers for any group.
 */
class ChamaViews(
    private val members: Members,
    private val goals: GoalRepository,
    private val contributions: ContributionRepository,
    private val requests: RequestRepository,
    private val wallets: Wallets,
    private val settings: SettingsRepository,
) {
    suspend fun view(userId: UserId, chatId: ChatId): Resource<ChamaView> {
        val everyone =
            when (val r = members.requireMember(chatId, userId)) {
                is Resource.Error -> return r
                is Resource.Success -> r.data
            }
        val goal = goals.find(chatId) ?: return Resource.error(ChamaError.NoGoal)
        val towards = contributions.totals(chatId, since = goal.createdAt)
        val all = contributions.totals(chatId).values.sum()
        val paidOut = requests.paidOutTotal(chatId)
        val admins = members.admins(chatId)
        val wallet = wallets.groupWallet(chatId)
        return Resource.success(
            ChamaView(
                goal = goal,
                memberCount = everyone.size,
                contributedMinor = towards.values.sum(),
                paidOutMinor = paidOut,
                heldMinor = (all - paidOut).coerceAtLeast(0),
                myContributedMinor = towards[userId] ?: 0,
                contributorsMinor = towards,
                openRequests = requests.open(chatId),
                isAdmin = userId in admins,
                isTreasurer = wallet?.signerId == userId,
                wallet = wallet,
                settings = settings.find(chatId),
            ),
        )
    }

    /** CH7: the group's money whether or not it has a target or a wallet. */
    suspend fun money(userId: UserId, chatId: ChatId): Resource<GroupMoney> {
        val everyone =
            when (val r = members.requireMember(chatId, userId)) {
                is Resource.Error -> return r
                is Resource.Success -> r.data
            }
        val goal = goals.find(chatId)
        val towards = if (goal == null) emptyMap() else contributions.totals(chatId, since = goal.createdAt)
        val wallet = wallets.groupWallet(chatId)
        return Resource.success(
            GroupMoney(
                chatId = chatId,
                wallet = wallet,
                settings = settings.find(chatId),
                goal = goal,
                memberCount = everyone.size,
                contributedMinor = towards.values.sum(),
                myContributedMinor = towards[userId] ?: 0,
                contributorsMinor = towards,
                paidOutMinor = requests.paidOutTotal(chatId),
                openRequests = requests.open(chatId),
                isAdmin = userId in members.admins(chatId),
                isOwner = members.owner(chatId) == userId,
                isSigner = wallet?.signerId == userId,
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
