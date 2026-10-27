package com.circaso.messaging.domain.usecases

import com.circaso.messaging.domain.models.InboxMessage
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.repositories.DeliveryRepository
import com.circaso.messaging.domain.repositories.KeyRepository
import com.circaso.messaging.domain.support.DeliveryNotifier
import com.circaso.messaging.domain.support.InboxRemoval
import com.circaso.messaging.domain.support.Membership
import com.circaso.shared.DeviceId
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Instant
import java.util.UUID

/**
 * `identity.DeviceReplaced` (Messaging → Encryption → Key changes). The old phone's key bundle is deleted unless
 * the new phone has already published its own, and so are the one-time prekeys written before the replacement.
 * Messages waiting in the inbox from before the replacement were encrypted for the old phone's keys and the new
 * phone can't open them, so they are dropped and each sender gets `message.undeliverable` to resend from its own
 * copy. Everyone who shares a chat with the person
 * gets `keys.changed`. Safe to run twice: a second run finds nothing left to drop and repeats only `keys.changed`.
 */
class RetireReplacedPhone(
    private val keys: KeyRepository,
    private val delivery: DeliveryRepository,
    private val removal: InboxRemoval,
    private val chats: ChatRepository,
    private val membership: Membership,
    private val notifier: DeliveryNotifier,
) {
    suspend operator fun invoke(
        userId: UserId,
        oldDeviceId: DeviceId,
        replacedAt: Instant,
    ): Resource<Unit> {
        keys.removeBundle(userId, oldDeviceId, replacedAt)
        for (row in waitingSince(userId, replacedAt)) {
            if (removal.remove(row)) notifier.undeliverable(row.senderId, row.chatId, row.messageId, userId)
        }
        notifier.keysChanged(contacts(userId), userId)
        return Resource.success(Unit)
    }

    /** Every inbox row sent up to [replacedAt], read a page at a time; the inbox is in sending order. */
    private suspend fun waitingSince(userId: UserId, replacedAt: Instant): List<InboxMessage> {
        val found = mutableListOf<InboxMessage>()
        var after: UUID? = null
        while (true) {
            val page = delivery.inbox(userId, after, PAGE)
            val older = page.takeWhile { !it.sentAt.isAfter(replacedAt) }
            found += older
            if (page.size < PAGE || older.size < page.size) return found
            after = page.last().messageId
        }
    }

    /** Everyone who shares a chat with [userId]. */
    private suspend fun contacts(userId: UserId): Set<UserId> =
        chats
            .chatsOf(userId)
            .flatMap { membership.members(it.chatId) }
            .toSet() - userId

    private companion object {
        const val PAGE = 500
    }
}
