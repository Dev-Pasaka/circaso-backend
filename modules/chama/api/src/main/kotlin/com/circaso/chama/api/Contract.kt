package com.circaso.chama.api

import com.circaso.messaging.api.ChatId

/** What other modules may ask a chama. The assistant reads it later ("who hasn't contributed this month?"). */
interface Chamas {
    /** Whether the group chat has a shared goal set. */
    suspend fun hasGoal(chatId: ChatId): Boolean
}
