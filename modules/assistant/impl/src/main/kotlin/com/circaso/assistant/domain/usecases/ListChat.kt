package com.circaso.assistant.domain.usecases

import com.circaso.assistant.domain.models.ChatPage
import com.circaso.assistant.domain.repositories.ChatRepository
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import java.util.UUID

/** A1: the Asha chat, newest first, paged by an opaque cursor. */
class ListChat(private val chat: ChatRepository) {
    suspend operator fun invoke(
        userId: UserId,
        cursor: String?,
        limit: Int,
    ): Resource<ChatPage> {
        val before =
            cursor?.let {
                runCatching { UUID.fromString(it) }.getOrNull()
                    ?: return Resource.error(SharedError.Malformed(mapOf("cursor" to "invalid")))
            }
        val size = limit.coerceIn(1, 100)
        val rows = chat.list(userId, before, size + 1)
        val shown = rows.take(size)
        return Resource.success(ChatPage(shown, if (rows.size > size) shown.last().messageId.toString() else null))
    }
}
