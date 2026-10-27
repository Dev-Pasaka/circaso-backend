package com.circaso.messaging.domain.usecases

import com.circaso.messaging.domain.ports.Transactor
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

/**
 * `identity.DeviceReplaced` (Messaging → Encryption → Key changes). The old phone's key bundle is deleted unless
 * the new phone has already published its own, and so are the old phone's one-time prekeys uploaded before the
 * replacement (`uploaded_at < :occurredAt`, decision 2026-10-27). Messages waiting in the inbox from before the
 * replacement were encrypted for the old phone's keys and the new phone can't open them, so they are taken out a page
 * at a time (`DELETE … RETURNING`) and each sender gets `message.undeliverable` to resend from its own copy. Everyone
 * who shares a chat with the person gets `keys.changed`. Safe to run twice: a second run finds nothing left to drop
 * and repeats only `keys.changed`.
 */
class RetireReplacedPhone(
    private val keys: KeyRepository,
    private val transactor: Transactor,
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
        transactor.run("keys.retire-phone") { keys.removeBundle(userId, oldDeviceId, replacedAt) }
        dropWaiting(userId, replacedAt)
        notifier.keysChanged(contacts(userId), userId)
        return Resource.success(Unit)
    }

    /** Every inbox row sent up to [replacedAt], a page per transaction; each page's senders are told after it. */
    private suspend fun dropWaiting(userId: UserId, replacedAt: Instant) {
        while (true) {
            val page = delivery.takeInboxRowsSentBy(userId, replacedAt, PAGE)
            removal.settle(page)
            for (row in page) notifier.undeliverable(row.senderId, row.chatId, row.messageId, userId)
            if (page.size < PAGE) return
        }
    }

    /** Everyone who shares a chat with [userId]. */
    private suspend fun contacts(userId: UserId): Set<UserId> =
        chats
            .chatIdsOf(userId)
            .flatMap { membership.members(it) }
            .toSet() - userId

    private companion object {
        const val PAGE = 500
    }
}
