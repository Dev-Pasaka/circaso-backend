package com.circaso.messaging.domain.usecases

import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.InviteState
import com.circaso.messaging.domain.repositories.InviteRepository
import com.circaso.messaging.domain.support.Invitations
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Clock
import java.util.UUID

/**
 * M25 `POST /chats/invites/{inviteId}/decline` (decision 2026-11-09): the invite is answered no and leaves the
 * person's inbox. Nobody is notified: the group's admins and the inviter get only `invites.changed`, which refreshes
 * group info's list without saying why, so a decline never turns into a message. Inviting the person again later makes
 * a new invite. A retried decline answers as the first did.
 */
class DeclineInvite(
    private val invites: InviteRepository,
    private val invitations: Invitations,
    private val clock: Clock,
) {
    suspend operator fun invoke(caller: UserId, inviteId: UUID): Resource<Unit> {
        val invite =
            invites.invite(inviteId)?.takeIf { it.userId == caller }
                ?: return Resource.error(MessagingError.InviteNotFound)
        if (invite.state == InviteState.DECLINED) return Resource.success(Unit)
        val now = clock.instant()
        if (!invite.isOpenAt(now) || !invites.decide(inviteId, InviteState.DECLINED, now)) {
            return Resource.error(MessagingError.InviteNotFound)
        }
        invitations.changed(invite.chatId, invite.invitedBy)
        return Resource.success(Unit)
    }
}
