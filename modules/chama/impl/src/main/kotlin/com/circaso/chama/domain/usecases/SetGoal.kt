package com.circaso.chama.domain.usecases

import com.circaso.chama.domain.errors.ChamaError
import com.circaso.chama.domain.models.Goal
import com.circaso.chama.domain.models.SetGoalCommand
import com.circaso.chama.domain.ports.ChamaFrame
import com.circaso.chama.domain.ports.FrameSender
import com.circaso.chama.domain.repositories.ContributionRepository
import com.circaso.chama.domain.repositories.GoalRepository
import com.circaso.chama.domain.support.Amounts
import com.circaso.chama.domain.support.Members
import com.circaso.shared.Resource
import com.circaso.wallet.api.Wallets
import java.time.Clock

/**
 * `PUT /chama/chats/{chatId}/goal` (CH2): an admin sets or changes the group's target. A target needs the group's
 * wallet (decision 2026-11-03, GW7): its money goes there, so without an active wallet the answer is
 * `chama.no_wallet` and the app walks the owner through setting one up first. Contributions count towards it from
 * when it is first set; changing it keeps that. Every member hears `goal.updated`.
 */
class SetGoal(
    private val members: Members,
    private val goals: GoalRepository,
    private val contributions: ContributionRepository,
    private val frames: FrameSender,
    private val clock: Clock,
    private val wallets: Wallets,
) {
    suspend operator fun invoke(command: SetGoalCommand): Resource<Goal> {
        val everyone =
            when (val r = members.requireAdmin(command.chatId, command.userId)) {
                is Resource.Error -> return r
                is Resource.Success -> members.members(command.chatId)
            }
        val name = command.name.trim()
        if (name.isEmpty() ||
            name.length > MAX_NAME
        ) {
            return Resource.error(ChamaError.GoalInvalid("name", "must be 1 to $MAX_NAME characters"))
        }
        if (command.targetMinor <= 0) return Resource.error(ChamaError.GoalInvalid("target", "must be above zero"))
        if (wallets.groupWallet(command.chatId)?.isActive != true) return Resource.error(ChamaError.NoWallet)

        val now = clock.instant()
        // A goal from the treasurer model starts over as the group wallet's target: what was paid to the treasurer
        // isn't in the group wallet.
        val existing = goals.find(command.chatId)?.takeIf { it.treasurerId == null }
        val goal =
            Goal(
                chatId = command.chatId,
                name = name,
                targetMinor = command.targetMinor,
                deadline = command.deadline,
                treasurerId = null,
                createdBy = existing?.createdBy ?: command.userId,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                version = (existing?.version ?: 0) + 1,
            )
        goals.save(goal)
        val contributed = contributions.totals(command.chatId, since = goal.createdAt).values.sum()
        frames.send(
            everyone,
            ChamaFrame.GoalUpdated(
                goal.chatId,
                goal.name,
                Amounts.usdc(goal.targetMinor),
                Amounts.usdc(contributed),
                command.userId,
            ),
        )
        return Resource.success(goal)
    }

    private companion object {
        const val MAX_NAME = 60
    }
}
