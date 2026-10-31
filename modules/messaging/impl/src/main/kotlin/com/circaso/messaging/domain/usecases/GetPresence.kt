package com.circaso.messaging.domain.usecases

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.models.Presence
import com.circaso.messaging.domain.support.ChatPresence
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.map
import java.time.Clock

/** `GET /v1/chats/{chatId}/presence`: the chat's other members' presence now, without watching them. */
class GetPresence(private val chatPresence: ChatPresence, private val clock: Clock) {
    suspend operator fun invoke(userId: UserId, chatId: ChatId): Resource<List<Presence>> =
        chatPresence.visibleOthers(userId, chatId).map { chatPresence.read(it, clock.instant()) }
}
