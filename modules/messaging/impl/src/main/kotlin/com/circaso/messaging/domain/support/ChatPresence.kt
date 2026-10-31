package com.circaso.messaging.domain.support

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.Presence
import com.circaso.messaging.domain.models.toPresence
import com.circaso.messaging.domain.ports.PresenceStore
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.api.ReachPolicy
import java.time.Instant

/**
 * Whose presence a person may see through a chat: its other members, only when the person is in it (else
 * `messaging.chat_not_found`, as for any chat they aren't in), nobody in a chat of more than 50 people (like typing),
 * and nobody either of them blocked (a shared chat never overrides a block, decision 2026-10-26).
 */
class ChatPresence(
    private val membership: Membership,
    private val reach: ReachPolicy,
    private val store: PresenceStore,
    private val config: MessagingConfig,
) {
    suspend fun visibleOthers(userId: UserId, chatId: ChatId): Resource<Set<UserId>> {
        val members = membership.members(chatId)
        if (userId !in members) return Resource.error(MessagingError.ChatNotFound)
        if (members.size > config.maxPresenceMembers) return Resource.success(emptySet())
        val others = (members - userId).filterNot { reach.blockedEitherWay(userId, it) }.toSet()
        return Resource.success(others)
    }

    /** [userIds]' presence at [now], in a stable order (by id). */
    suspend fun read(userIds: Set<UserId>, now: Instant): List<Presence> {
        val records = store.read(userIds)
        return userIds
            .sortedBy { it.toString() }
            .map { records[it].toPresence(it, now, config.presenceTtl) }
    }
}
