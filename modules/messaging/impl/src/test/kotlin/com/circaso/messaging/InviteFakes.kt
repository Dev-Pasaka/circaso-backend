package com.circaso.messaging

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.models.Invite
import com.circaso.messaging.domain.models.InviteState
import com.circaso.messaging.domain.repositories.InviteRepository
import com.circaso.shared.UserId
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Group invites in memory, with the table's rules: one open invite per person per group (an expired one is closed
 * first), and a decision that only moves a pending invite. Invites go with their chat, as `ON DELETE CASCADE` does.
 */
class MemoryInvites(private val chats: MemoryChats) : InviteRepository {
    val invites = ConcurrentHashMap<UUID, Invite>()

    private fun live() = invites.values.filter { chats.chats.containsKey(it.chatId) }

    override suspend fun invite(inviteId: UUID) = invites[inviteId]?.takeIf { chats.chats.containsKey(it.chatId) }

    override suspend fun lockInvite(inviteId: UUID) = invite(inviteId)

    override suspend fun openFor(
        chatId: ChatId,
        userIds: Collection<UserId>,
        now: Instant,
    ) = live().filter { it.chatId == chatId && it.userId in userIds && it.isOpenAt(now) }

    override suspend fun insert(invites: List<Invite>, now: Instant) {
        synchronized(this) {
            for (invite in invites) {
                val clash =
                    this.invites.values.firstOrNull {
                        it.chatId == invite.chatId && it.userId == invite.userId && it.state == InviteState.PENDING
                    }
                if (clash != null) {
                    check(!clash.expiresAt.isAfter(now)) { "a second open invite for one person" }
                    this.invites[clash.inviteId] = clash.copy(state = InviteState.CANCELLED)
                }
                this.invites[invite.inviteId] = invite
            }
        }
    }

    override suspend fun decide(
        inviteId: UUID,
        state: InviteState,
        at: Instant,
    ): Boolean =
        synchronized(this) {
            val stored = invites[inviteId] ?: return false
            if (stored.state != InviteState.PENDING) return false
            invites[inviteId] = stored.copy(state = state)
            true
        }

    override suspend fun openOf(userId: UserId, now: Instant) =
        live().filter { it.userId == userId && it.isOpenAt(now) }.sortedByDescending { it.createdAt }

    override suspend fun openIn(chatId: ChatId, now: Instant) =
        live().filter { it.chatId == chatId && it.isOpenAt(now) }.sortedBy { it.createdAt }

    /** [userId]'s open invite to [chatId], by id, for tests that answer it. */
    fun openId(chatId: ChatId, userId: UserId): UUID =
        invites.values
            .single { it.chatId == chatId && it.userId == userId && it.state == InviteState.PENDING }
            .inviteId
}
