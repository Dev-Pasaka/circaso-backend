package com.circaso.messaging.domain.repositories

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.models.Invite
import com.circaso.messaging.domain.models.InviteState
import com.circaso.shared.UserId
import java.time.Instant
import java.util.UUID

/**
 * Group invites: `invites`. Writes that depend on the group (sending, accepting) run inside a `Transactor` block after
 * the chat's row is locked, the lock order every membership change keeps.
 */
interface InviteRepository {
    suspend fun invite(inviteId: UUID): Invite?

    /** The invite, its row locked until the transaction ends (`FOR UPDATE`). Inside a `Transactor` block only. */
    suspend fun lockInvite(inviteId: UUID): Invite?

    /** The open invites to [chatId] of any of [userIds] (pending and not expired at [now]). */
    suspend fun openFor(
        chatId: ChatId,
        userIds: Collection<UserId>,
        now: Instant,
    ): List<Invite>

    /**
     * Inserts [invites], first closing any pending invite of the same people to the same group that expired by [now]
     * (it would hold the one-open-invite index until the purge). The caller checked, under the chat's lock, that none
     * of them has an open one.
     */
    suspend fun insert(invites: List<Invite>, now: Instant)

    /**
     * Moves a pending invite to [state] (`ACCEPTED`, `DECLINED` or `CANCELLED`) only if it is still pending; true when
     * this call moved it.
     */
    suspend fun decide(
        inviteId: UUID,
        state: InviteState,
        at: Instant,
    ): Boolean

    /** [userId]'s open invites, newest first. */
    suspend fun openOf(userId: UserId, now: Instant): List<Invite>

    /** [chatId]'s open invites, oldest first. */
    suspend fun openIn(chatId: ChatId, now: Instant): List<Invite>
}
