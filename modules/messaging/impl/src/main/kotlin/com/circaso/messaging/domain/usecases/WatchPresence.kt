package com.circaso.messaging.domain.usecases

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.models.Presence
import com.circaso.messaging.domain.ports.PresenceWatchers
import com.circaso.messaging.domain.support.ChatPresence
import com.circaso.messaging.domain.support.DeliveryNotifier
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.flatMap
import java.time.Clock

/**
 * `messaging.presence.watch` from the phone, when a chat opens and every 45 seconds while it stays open: for the
 * next 60 seconds the caller hears each change of the chat's other members, and gets one `presence.changed` per
 * member now. Refused (`messaging.chat_not_found`) for a chat the caller isn't in; see [ChatPresence] for who is left
 * out.
 */
class WatchPresence(
    private val chatPresence: ChatPresence,
    private val watchers: PresenceWatchers,
    private val notifier: DeliveryNotifier,
    private val config: MessagingConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(userId: UserId, chatId: ChatId): Resource<List<Presence>> =
        chatPresence.visibleOthers(userId, chatId).flatMap { others ->
            val now = clock.instant()
            if (others.isNotEmpty()) watchers.watch(userId, others, now.plus(config.presenceWatch))
            val presences = chatPresence.read(others, now)
            presences.forEach { notifier.presence(listOf(userId), it) }
            Resource.success(presences)
        }
}
