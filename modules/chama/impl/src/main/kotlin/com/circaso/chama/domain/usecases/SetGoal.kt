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
import java.time.Clock

/**
 * `PUT /chama/chats/{chatId}/goal`: an admin sets or changes the group's goal. The treasurer (an admin or any member
 * named; the caller by default) is whose wallet holds the money. Every member hears `goal.updated`.
 */
class SetGoal(
    private val members: Members,
    private val goals: GoalRepository,
    private val contributions: ContributionRepository,
    private val frames: FrameSender,
    private val clock: Clock,
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
        val treasurer = command.treasurerId ?: command.userId
        if (treasurer !in everyone) return Resource.error(ChamaError.TreasurerNotMember)

        val now = clock.instant()
        val existing = goals.find(command.chatId)
        val goal =
            Goal(
                chatId = command.chatId,
                name = name,
                targetMinor = command.targetMinor,
                deadline = command.deadline,
                treasurerId = treasurer,
                createdBy = existing?.createdBy ?: command.userId,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                version = (existing?.version ?: 0) + 1,
            )
        goals.save(goal)
        val contributed = contributions.totals(command.chatId).values.sum()
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
