package com.circaso.messaging.domain.support

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.Chat
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.shared.Resource

/**
 * Changes a chat through its version (decision 2026-10-26: every membership decision is made inside the version
 * check). [change] sees the chat as it is now and re-reads whatever rows it decides on, every attempt; it answers
 * the new chat, or an error that stops (the chat is full, the caller isn't an admin any more). When another change
 * wins the version in between, it runs again on the newer chat, a few times at most.
 */
class ChatWriter(private val chats: ChatRepository, private val config: MessagingConfig) {
    suspend fun update(chatId: ChatId, change: suspend (Chat) -> Resource<Chat>): Resource<Chat> {
        repeat(config.chatWriteAttempts) {
            val current = chats.chat(chatId) ?: return Resource.error(MessagingError.ChatNotFound)
            val next =
                when (val decided = change(current)) {
                    is Resource.Error -> return decided
                    is Resource.Success -> decided.data
                }
            chats.updateChat(current, next)?.let { return Resource.success(it) }
        }
        return Resource.error(MessagingError.VersionConflict)
    }

    /**
     * Sets the member count to the rows there are, after a write that failed part-way, so the count is never below
     * the rows (and not left above them either). Best effort: the next membership change counts again.
     */
    suspend fun recount(chatId: ChatId) {
        update(chatId) { latest -> Resource.success(latest.copy(memberCount = chats.members(chatId).size)) }
    }
}
