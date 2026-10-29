package com.circaso.chama.domain.usecases

import com.circaso.chama.domain.repositories.GoalRepository
import com.circaso.messaging.api.ChatId

/** `Chamas.hasGoal`, for other modules. */
class HasGoal(private val goals: GoalRepository) {
    suspend operator fun invoke(chatId: ChatId): Boolean = goals.find(chatId) != null
}
